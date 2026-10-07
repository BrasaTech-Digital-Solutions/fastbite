package es.brasatech.fastbite.service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import es.brasatech.fastbite.domain.order.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Service
public class StripeService {

    public static final String RESTAURANT_ORDER = "RESTAURANT_ORDER";
    private static final String CURRENCY = "eur";

    private final String secretKey;
    private final String webhookSecret;

    @Value("${stripe.platform.webhook.secret:}")
    private String platformWebhookSecret;

    public StripeService(
            @Value("${stripe.secret.key:}") String secretKey,
            @Value("${stripe.webhook.secret:}") String webhookSecret) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        Stripe.apiKey = secretKey;
    }

    /** Whether guests can pay online at all. */
    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    private void requireSecretKey() {
        if (!isConfigured()) {
            throw new IllegalStateException("Online payment is not set up: STRIPE_SECRET_KEY is missing.");
        }
    }

    /**
     * Level 2: a guest pays a restaurant order. The amount is the order's saved total plus the tip;
     * the session carries the restaurant and order so the payment can be matched to them afterwards.
     *
     * @param connectedAccountId the restaurant's Stripe account the money goes to, or blank to keep it on the platform account
     */
    public Session createOrderCheckoutSession(String tenantId, Order order, BigDecimal tip, String connectedAccountId,
            String successUrl, String cancelUrl) throws StripeException {
        return createOrderCheckoutSession(tenantId, order, tip, connectedAccountId, 0L, successUrl, cancelUrl);
    }

    public Session createOrderCheckoutSession(String tenantId, Order order, BigDecimal tip, String connectedAccountId,
            long applicationFeeAmountCents, String successUrl, String cancelUrl) throws StripeException {
        requireSecretKey();

        Map<String, String> metadata = new HashMap<>();
        metadata.put("tenantId", tenantId);
        metadata.put("orderId", order.id());
        metadata.put("orderNumber", String.valueOf(order.orderNumber()));
        metadata.put("tip", tip.toPlainString());
        metadata.put("type", RESTAURANT_ORDER);

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setClientReferenceId(order.id())
                .putAllMetadata(metadata)
                .addLineItem(lineItem("Order #" + order.orderNumber(), order.total()));
        if (tip.signum() > 0) {
            builder.addLineItem(lineItem("Tip", tip));
        }
        if (connectedAccountId != null && !connectedAccountId.isBlank()) {
            // Destination charge: the platform takes the payment and passes it on to the restaurant's account
            var paymentIntentBuilder = SessionCreateParams.PaymentIntentData.builder()
                    .setTransferData(SessionCreateParams.PaymentIntentData.TransferData.builder()
                            .setDestination(connectedAccountId.trim())
                            .build());
            if (applicationFeeAmountCents > 0) {
                paymentIntentBuilder.setApplicationFeeAmount(applicationFeeAmountCents);
            }
            builder.setPaymentIntentData(paymentIntentBuilder.build());
        }
        return Session.create(builder.build());
    }

    private static SessionCreateParams.LineItem lineItem(String name, BigDecimal amount) {
        return SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(CURRENCY)
                        .setUnitAmount(toCents(amount))
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName(name)
                                .build())
                        .build())
                .build();
    }

    public static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The checkout session as Stripe has it now: the trusted source for whether and how much was paid. */
    public Session retrieveSession(String sessionId) throws StripeException {
        requireSecretKey();
        return Session.retrieve(sessionId);
    }

    /**
     * Parses a webhook call after checking Stripe's signature on it.
     *
     * @throws SignatureVerificationException if the call is unsigned or the signature does not match
     */
    public Event constructEvent(String payload, String sigHeader) throws SignatureVerificationException {
        if (platformWebhookSecret != null && !platformWebhookSecret.isBlank() && sigHeader != null) {
            try { return Webhook.constructEvent(payload, sigHeader, platformWebhookSecret); }
            catch (SignatureVerificationException ignored) { /* Try the restaurant endpoint secret below. */ }
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new SignatureVerificationException("Webhooks are refused: STRIPE_CONNECT_WEBHOOK_SECRET is missing", sigHeader);
        }
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new SignatureVerificationException("Missing Stripe-Signature header", sigHeader);
        }
        return Webhook.constructEvent(payload, sigHeader, webhookSecret);
    }

    /**
     * Creates a Stripe Express connected account for a restaurant tenant.
     */
    public com.stripe.model.Account createConnectedAccount(String tenantId, String email, String businessName) throws StripeException {
        requireSecretKey();
        com.stripe.param.AccountCreateParams.Builder builder = com.stripe.param.AccountCreateParams.builder()
                .setType(com.stripe.param.AccountCreateParams.Type.EXPRESS)
                .putMetadata("tenantId", tenantId);
        if (email != null && !email.isBlank()) {
            builder.setEmail(email.trim());
        }
        if (businessName != null && !businessName.isBlank()) {
            builder.setBusinessProfile(com.stripe.param.AccountCreateParams.BusinessProfile.builder()
                    .setName(businessName.trim())
                    .build());
        }
        return com.stripe.model.Account.create(builder.build());
    }

    /**
     * Generates a hosted onboarding link for the connected Stripe account.
     */
    public com.stripe.model.AccountLink createAccountLink(String accountId, String refreshUrl, String returnUrl) throws StripeException {
        requireSecretKey();
        com.stripe.param.AccountLinkCreateParams params = com.stripe.param.AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setRefreshUrl(refreshUrl)
                .setReturnUrl(returnUrl)
                .setType(com.stripe.param.AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build();
        return com.stripe.model.AccountLink.create(params);
    }
}
