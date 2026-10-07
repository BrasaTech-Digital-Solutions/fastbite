package es.brasatech.fastbite.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import es.brasatech.fastbite.service.OrderCheckoutService;
import es.brasatech.fastbite.service.StripeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import es.brasatech.fastbite.application.tenant.OwnerSetupService;
import es.brasatech.fastbite.application.tenant.OwnerSetupPort;
import java.util.Map;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser
@WebMvcTest(controllers = StripeWebhookController.class)
@DisplayName("StripeWebhookController Tests")
@ContextConfiguration(classes = {TestConfig.class, StripeWebhookController.class, es.brasatech.fastbite.security.SecurityConfig.class})
class StripeWebhookControllerTest {

    @MockitoBean
    private es.brasatech.fastbite.application.tenant.SubscriptionService subscriptions;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StripeService stripeService;

    @MockitoBean
    private TenantSignupService tenantSignupService;

    @MockitoBean
    private TenantLocationService tenantLocationService;

    @MockitoBean
    private OrderCheckoutService orderCheckoutService;

    @MockitoBean
    private OwnerSetupService ownerSetupService;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/webhooks/stripe - Platform level event should auto-provision tenant")
    void testHandleStripeWebhookPlatformLevel() throws Exception {
        String payload = """
            {
              "id": "evt_test_123",
              "object": "event",
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "cs_test_999",
                  "client_reference_id": "stripeburger",
                  "metadata": {
                    "tenantId": "stripeburger",
                    "username": "stripeadmin",
                    "fullName": "Stripe Owner",
                    "type": "PLATFORM_SUBSCRIPTION"
                  }
                }
              }
            }
            """;

        Event mockEvent = Event.GSON.fromJson(payload, Event.class);
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(mockEvent);
        Session paid = new Session();
        paid.setPaymentStatus("paid");
        paid.setCustomerEmail("owner@example.test");
        paid.setMetadata(Map.of("tenantId", "stripeburger", "username", "stripeadmin", "fullName", "Stripe Owner", "type", "PLATFORM_SUBSCRIPTION"));
        when(stripeService.retrieveSession("cs_test_999")).thenReturn(paid);

        mockMvc.perform(post("/api/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(csrf()))
                .andDo(print())
                .andExpect(status().isOk());

        verify(ownerSetupService).invite(new OwnerSetupPort.Invitation("cs_test_999", "stripeburger", "stripeadmin", "Stripe Owner", "owner@example.test", "RESTAURANT"));
        verify(tenantSignupService, never()).registerTenant(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /api/webhooks/stripe - Restaurant level event should mark order paid")
    void testHandleStripeWebhookRestaurantLevel() throws Exception {
        String payload = """
            {
              "id": "evt_test_456",
              "object": "event",
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "cs_test_888",
                  "client_reference_id": "ORD-101",
                  "metadata": {
                    "tenantId": "kebab",
                    "orderId": "ORD-101",
                    "type": "RESTAURANT_ORDER"
                  }
                }
              }
            }
            """;

        Event mockEvent = Event.GSON.fromJson(payload, Event.class);
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(mockEvent);
        // The payment is judged on the session as Stripe returns it, not on what the call says
        Session fromStripe = new Session();
        when(stripeService.retrieveSession("cs_test_888")).thenReturn(fromStripe);

        mockMvc.perform(post("/api/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(csrf()))
                .andDo(print())
                .andExpect(status().isOk());

        verify(orderCheckoutService).confirm(fromStripe);
    }

    @Test
    @DisplayName("POST /api/webhooks/stripe - A call without a valid Stripe signature changes nothing")
    void testUnsignedWebhookIsRefused() throws Exception {
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenThrow(new SignatureVerificationException("Missing Stripe-Signature header", null));

        mockMvc.perform(post("/api/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_fake\","
                                + "\"metadata\":{\"tenantId\":\"kebab\",\"orderId\":\"ORD-101\",\"type\":\"RESTAURANT_ORDER\"}}}}"))
                .andExpect(status().isBadRequest());

        verify(orderCheckoutService, never()).confirm(org.mockito.ArgumentMatchers.any());
        verify(stripeService, never()).retrieveSession(org.mockito.ArgumentMatchers.any());
        verify(tenantSignupService, never()).registerTenant(anyString(), anyString(), anyString(), anyString());
    }
    private void platformEvent(String type, String paymentStatus) throws Exception {
        String payload = "{\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"cs_setup\",\"metadata\":{\"type\":\"PLATFORM_SUBSCRIPTION\"}}}}";
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(Event.GSON.fromJson(payload, Event.class));
        Session paid = new Session();
        paid.setPaymentStatus(paymentStatus);
        paid.setCustomerEmail("owner@example.test");
        paid.setMetadata(Map.of("type", "PLATFORM_SUBSCRIPTION", "tenantId", "fresh", "username", "newowner"));
        when(stripeService.retrieveSession("cs_setup")).thenReturn(paid);
        mockMvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
    }

    @Test void additionalLocationsUseCheckoutIdentityAndPropagateRetryableFailure() throws Exception {
        String payload = "{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_location\",\"metadata\":{\"type\":\"PLATFORM_SUBSCRIPTION\"}}}}";
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(Event.GSON.fromJson(payload, Event.class));
        Session paid = new Session();
        paid.setPaymentStatus("paid");
        paid.setMetadata(Map.of("type", "PLATFORM_SUBSCRIPTION", "tenantId", "newlocation", "ownerUsername", "owner", "plan", "RESTAURANT"));
        when(stripeService.retrieveSession("cs_location")).thenReturn(paid);
        org.mockito.Mockito.doThrow(new IllegalStateException("Busy")).doNothing().when(tenantSignupService)
                .registerAdditionalLocation("newlocation", "owner", "RESTAURANT", "checkout:cs_location");
        mockMvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isInternalServerError());
        mockMvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
        verify(tenantSignupService, org.mockito.Mockito.times(2))
                .registerAdditionalLocation("newlocation", "owner", "RESTAURANT", "checkout:cs_location");
    }

    @Test void unpaidCheckoutDoesNotProvisionAnOwner() throws Exception {
        platformEvent("checkout.session.completed", "unpaid");
        org.mockito.Mockito.verifyNoInteractions(ownerSetupService);
    }

    @Test void subscriptionCreatedDoesNotProvisionAnOwner() throws Exception {
        platformEvent("customer.subscription.created", "paid");
        org.mockito.Mockito.verifyNoInteractions(ownerSetupService);
        verify(stripeService, never()).retrieveSession(anyString());
    }

    @Test void delayedPaymentSuccessCanSendTheSetupLink() throws Exception {
        platformEvent("checkout.session.async_payment_succeeded", "paid");
        verify(ownerSetupService).invite(new OwnerSetupPort.Invitation("cs_setup", "fresh", "newowner", "Restaurant Owner", "owner@example.test", "RESTAURANT"));
    }


    @Test void recurringCheckoutWithNoImmediatePaymentSynchronizesSubscriptionInsteadOfProvisioning() throws Exception {
        String payload = "{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"subscription-checkout\",\"metadata\":{\"type\":\"PLATFORM_SUBSCRIPTION\"}}}}";
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(Event.GSON.fromJson(payload, Event.class));
        var session=new Session();session.setMode("subscription");session.setPaymentStatus("no_payment_required");
        when(stripeService.retrieveSession("subscription-checkout")).thenReturn(session);
        mockMvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
        verify(subscriptions).refreshCheckout("subscription-checkout");
        verify(ownerSetupService,never()).invite(org.mockito.ArgumentMatchers.any());
    }
    @Test void subscriptionUpdatesRefetchCurrentStripeStateAndFailuresAreRetryable() throws Exception {
        String payload = "{\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"sub_example\"}}}";
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(Event.GSON.fromJson(payload, Event.class));
        org.mockito.Mockito.doThrow(new IllegalStateException("Retry")).when(subscriptions).refreshSubscription("sub_example");
        mockMvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isInternalServerError());
        verify(subscriptions).refreshSubscription("sub_example");
    }
    @Test void invoiceFailureSynchronizesSubscriptionFromNewInvoiceShape() throws Exception {
        String payload = "{\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"parent\":{\"subscription_details\":{\"subscription\":\"sub_invoice\"}}}}}";
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(Event.GSON.fromJson(payload, Event.class));
        mockMvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
        verify(subscriptions).refreshSubscription("sub_invoice");
    }

    @Test
    @DisplayName("POST /api/webhooks/stripe - account.updated synchronizes Stripe Connect capabilities")
    void testAccountUpdatedWebhookSynchronizesCapabilities() throws Exception {
        String payload = """
            {
              "id": "evt_account_123",
              "object": "event",
              "type": "account.updated",
              "data": {
                "object": {
                  "id": "acct_test_connect",
                  "charges_enabled": true,
                  "details_submitted": true
                }
              }
            }
            """;
        when(stripeService.constructEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(Event.GSON.fromJson(payload, Event.class));

        mockMvc.perform(post("/api/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(csrf()))
                .andExpect(status().isOk());

        verify(tenantLocationService).syncStripeCapabilities("acct_test_connect", true, true);
    }
}
