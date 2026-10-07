package es.brasatech.fastbite.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import es.brasatech.fastbite.service.OrderCheckoutService;
import es.brasatech.fastbite.service.StripeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import es.brasatech.fastbite.application.tenant.OwnerSetupService;
import es.brasatech.fastbite.application.tenant.OwnerSetupPort;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    private final StripeService stripeService;
    private final es.brasatech.fastbite.application.tenant.SubscriptionService subscriptions;
    private final TenantSignupService tenantSignupService;
    private final OrderCheckoutService orderCheckoutService;
    private final OwnerSetupService ownerSetupService;
    private final es.brasatech.fastbite.application.tenant.TenantLocationService locationService;
    private final ObjectMapper objectMapper;

    @PostMapping("/api/webhooks/stripe")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        log.info("Received Stripe webhook notification");
        try {
            Event event = stripeService.constructEvent(payload, sigHeader);
            String eventType = event != null && event.getType() != null ? event.getType() : "";

            if (eventType.isEmpty()) {
                JsonNode root = objectMapper.readTree(payload);
                eventType = root.path("type").asText();
            }
            log.info("Processing verified Stripe event type: {}", eventType);

            if ("account.updated".equals(eventType) || "capability.updated".equals(eventType)) {
                JsonNode accountObj = objectMapper.readTree(payload).path("data").path("object");
                String accountId = accountObj.path("id").asText();
                if (accountId.isBlank() && accountObj.has("account")) {
                    accountId = accountObj.path("account").asText();
                }
                if (!accountId.isBlank()) {
                    boolean chargesEnabled = accountObj.path("charges_enabled").asBoolean(false);
                    boolean detailsSubmitted = accountObj.path("details_submitted").asBoolean(false);
                    locationService.syncStripeCapabilities(accountId, chargesEnabled, detailsSubmitted);
                    log.info("Synchronized Stripe Connect account {} capabilities: chargesEnabled={}, detailsSubmitted={}",
                            accountId, chargesEnabled, detailsSubmitted);
                    return ResponseEntity.ok("Account synchronized");
                }
            }

            if (eventType.startsWith("customer.subscription.")) {
                String id = objectMapper.readTree(payload).path("data").path("object").path("id").asText();
                if (id.isBlank()) return ResponseEntity.badRequest().body("Missing subscription");
                subscriptions.refreshSubscription(id);
                return ResponseEntity.ok("Subscription synchronized");
            }
            if ("invoice.paid".equals(eventType) || "invoice.payment_failed".equals(eventType)) {
                JsonNode invoice = objectMapper.readTree(payload).path("data").path("object");
                String id = invoice.path("subscription").asText("");
                if (id.isBlank()) id = invoice.path("parent").path("subscription_details").path("subscription").asText("");
                if (!id.isBlank()) subscriptions.refreshSubscription(id);
                return ResponseEntity.ok("Invoice synchronized");
            }

            if ("checkout.session.completed".equals(eventType) || "checkout.session.async_payment_succeeded".equals(eventType)
                   ) {
                Session session = null;
                if (event != null) {
                    try {
                        var dataObjectDeserializer = event.getDataObjectDeserializer();
                        if (dataObjectDeserializer != null && dataObjectDeserializer.getObject().isPresent() && dataObjectDeserializer.getObject().get() instanceof Session s) {
                            session = s;
                        } else if (dataObjectDeserializer != null) {
                            if (dataObjectDeserializer.deserializeUnsafe() instanceof Session s) {
                                session = s;
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Stripe event deserialization skipped: {}", e.getMessage());
                    }
                }

                Map<String, String> metadata = new HashMap<>();
                String clientReferenceId = "";
                String checkoutSessionId = session != null ? session.getId() : null;

                if (session != null) {
                    if (session.getMetadata() != null) {
                        metadata.putAll(session.getMetadata());
                    }
                    if (session.getClientReferenceId() != null) {
                        clientReferenceId = session.getClientReferenceId();
                    }
                } else {
                    try {
                        JsonNode root = objectMapper.readTree(payload);
                        JsonNode obj = root.path("data").path("object");
                        clientReferenceId = obj.path("client_reference_id").asText();
                        checkoutSessionId = obj.path("id").asText();
                        JsonNode metaNode = obj.path("metadata");
                        if (metaNode.isObject()) {
                            metaNode.forEachEntry((key, val) -> metadata.put(key, val.asText()));
                        }
                    } catch (Exception ex) {
                        log.warn("Could not parse payload JsonNode fallback: {}", ex.getMessage());
                    }
                }

                String tenantId = metadata.getOrDefault("tenantId", clientReferenceId);
                String orderId = metadata.getOrDefault("orderId", clientReferenceId);
                String paymentType = metadata.getOrDefault("type", "");

                // ===== LEVEL 2: RESTAURANT LEVEL PAYMENT (FOOD ORDER) =====
                if ("RESTAURANT_ORDER".equalsIgnoreCase(paymentType) || (!orderId.isEmpty() && !"PLATFORM_SUBSCRIPTION".equalsIgnoreCase(paymentType))) {
                    if (tenantId.isEmpty()) {
                        log.warn("Restaurant order webhook missing tenantId!");
                        return ResponseEntity.badRequest().body("Missing tenant identifier for order payment");
                    }
                    log.info("Processing Restaurant Order Payment for order: {} in tenant: {}", orderId, tenantId);
                    // What was paid, and for which order, is read back from Stripe rather than from this call
                    if (checkoutSessionId == null || checkoutSessionId.isBlank()) {
                        return ResponseEntity.badRequest().body("Missing checkout session");
                    }
                    orderCheckoutService.confirm(stripeService.retrieveSession(checkoutSessionId));
                } 
                // ===== LEVEL 1: PLATFORM LEVEL PAYMENT (OWNER SUBSCRIPTION / LOCATION PROVISIONING) =====
                else {
                    // Re-read the paid checkout, including its customer email and metadata, from Stripe.
                    // Subscription objects alone are not proof of a completed checkout.
                    if (checkoutSessionId == null || checkoutSessionId.isBlank()) {
                        return ResponseEntity.badRequest().body("Missing checkout session");
                    }
                    Session paid = stripeService.retrieveSession(checkoutSessionId);
                    if ("subscription".equals(paid.getMode())) {
                        subscriptions.refreshCheckout(checkoutSessionId);
                        return ResponseEntity.ok("Subscription checkout synchronized");
                    }
                    if (!"paid".equals(paid.getPaymentStatus())) {
                        return ResponseEntity.ok("Awaiting payment");
                    }
                    Map<String, String> paidMetadata = paid.getMetadata();
                    if (paidMetadata == null || !"PLATFORM_SUBSCRIPTION".equals(paidMetadata.get("type"))) {
                        return ResponseEntity.badRequest().body("Invalid platform checkout");
                    }
                    String paidTenant = paidMetadata.getOrDefault("tenantId", "");
                    String ownerUsername = paidMetadata.getOrDefault("ownerUsername", "");
                    String plan = "RESTAURANT"; // Legacy one-time payments get a transition trial, never recurring paid status.
                    if (!ownerUsername.isBlank()) {
                        tenantSignupService.registerAdditionalLocation(paidTenant, ownerUsername, plan,
                                "checkout:" + checkoutSessionId);
                    } else {
                        String email = paid.getCustomerDetails() != null ? paid.getCustomerDetails().getEmail() : null;
                        if (email == null || email.isBlank()) email = paid.getCustomerEmail();
                        ownerSetupService.invite(new OwnerSetupPort.Invitation(checkoutSessionId, paidTenant,
                                paidMetadata.getOrDefault("username", ""),
                                paidMetadata.getOrDefault("fullName", "Restaurant Owner"), email, plan));
                    }
                }
            }

            return ResponseEntity.ok("Webhook processed successfully");
        } catch (SignatureVerificationException e) {
            log.warn("Refused Stripe webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid signature");
        } catch (Exception e) {
            log.error("Stripe webhook processing failed ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body("Error processing webhook");
        }
    }
}
