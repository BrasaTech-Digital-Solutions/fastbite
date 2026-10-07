package es.brasatech.fastbite.service;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCheckoutServiceTest {

    private static final String DEFAULT_ACCOUNT = "acct_default";

    private final StripeService stripe = mock(StripeService.class);
    private final OrderService orders = mock(OrderService.class);
    private final TenantLocationService locations = mock(TenantLocationService.class);
    private final OrderCheckoutService checkout = new OrderCheckoutService(stripe, orders, locations, DEFAULT_ACCOUNT);

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order(List.of(new CartItem("l1", "kebab", "Kebab", null, null, 2, List.of(), new BigDecimal("6.75"))),
                12, OrderPaymentStatus.UNPAID, OrderChannel.TABLE, "en"); // total 13.50
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        when(locations.getLocation("kebab")).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Session session(String paymentStatus, long amountTotal, String type) {
        Session session = new Session();
        session.setId("cs_test_1");
        session.setPaymentStatus(paymentStatus);
        session.setAmountTotal(amountTotal);
        session.setMetadata(Map.of("tenantId", "kebab", "orderId", order.id(), "type", type));
        return session;
    }

    // ---- starting a checkout

    @Test
    void theCheckoutIsForTheOrderTotalPlusTheChosenTip() throws Exception {
        checkout.start("kebab", order, 15, "https://ok", "https://back");

        // 15% of 13.50 = 2.025, rounded to cents
        verify(stripe).createOrderCheckoutSession("kebab", order, new BigDecimal("2.03"), DEFAULT_ACCOUNT, 0L, "https://ok", "https://back");
    }

    @Test
    void theMoneyGoesToTheRestaurantsOwnStripeAccountWhenItHasOne() throws Exception {
        when(locations.getLocation("kebab")).thenReturn(Optional.of(
                new TenantLocation("1", "owner", "kebab", "RESTAURANT", null, "acct_kebab")));

        checkout.start("kebab", order, 0, "https://ok", "https://back");

        verify(stripe).createOrderCheckoutSession(eq("kebab"), eq(order), eq(new BigDecimal("0.00")), eq("acct_kebab"), eq(0L), any(), any());
    }

    @Test
    void freePlanTenantChargesApplicationFeeCut() throws Exception {
        when(locations.getLocation("kebab")).thenReturn(Optional.of(
                new TenantLocation("1", "owner", "kebab", "FREE", null, "acct_kebab")));

        // Order total 13.50 (1350 cents) -> 2.5% fee = 33 cents (33.75 floored to 33)
        checkout.start("kebab", order, 0, "https://ok", "https://back");

        verify(stripe).createOrderCheckoutSession(eq("kebab"), eq(order), eq(new BigDecimal("0.00")), eq("acct_kebab"), eq(33L), any(), any());
    }

    @Test
    void ordersThatCannotBePaidAndTipsNotOnOfferAreRefused() throws Exception {
        assertThatThrownBy(() -> checkout.start("kebab", order.setPaymentStatus(OrderPaymentStatus.PAID), 0, "a", "b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already paid");
        assertThatThrownBy(() -> checkout.start("kebab", order.cancel("no show"), 0, "a", "b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cancelled");
        assertThatThrownBy(() -> checkout.start("kebab", order, -50, "a", "b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkout.start("kebab", order, 7, "a", "b"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(stripe, never()).createOrderCheckoutSession(any(), any(), any(), any(), anyLong(), any(), any());
    }

    // ---- confirming a payment

    @Test
    void anExpiredOnlineOrderCannotStartAnotherCheckout() throws Exception {
        var old = java.time.LocalDateTime.now().minusHours(2);
        var expired = new Order(order.items(), 12, order.id(), old, old,
                es.brasatech.fastbite.domain.order.OrderStatus.CREATED, order.total(), null,
                OrderPaymentStatus.UNPAID, OrderChannel.ONLINE, "en", null, "Guest",
                es.brasatech.fastbite.domain.order.ServiceType.TAKEAWAY);
        assertThatThrownBy(() -> checkout.start("kebab", expired, 0, "a", "b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expired");
        verify(stripe, never()).createOrderCheckoutSession(any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void aCheckoutPaidInFullMarksTheOrderPaid() {
        assertThat(checkout.confirm(session("paid", 1350, StripeService.RESTAURANT_ORDER))).isTrue();
        assertThat(checkout.confirm(session("paid", 1553, StripeService.RESTAURANT_ORDER))).as("with a tip").isTrue();

        verify(orders, org.mockito.Mockito.times(2)).markOrderPaid(order.id());
    }

    @Test
    void anUnpaidOrUnderpaidCheckoutLeavesTheOrderUnpaid() {
        assertThat(checkout.confirm(session("unpaid", 1350, StripeService.RESTAURANT_ORDER))).isFalse();
        assertThat(checkout.confirm(session("paid", 1349, StripeService.RESTAURANT_ORDER))).isFalse();
        assertThat(checkout.confirm(session("paid", 1350, "PLATFORM_SUBSCRIPTION"))).isFalse();

        verify(orders, never()).markOrderPaid(any());
    }

    @Test
    void confirmingTwiceOrAMissingOrderDoesNothingMore() {
        when(orders.findById(order.id())).thenReturn(Optional.of(order.setPaymentStatus(OrderPaymentStatus.PAID)));
        assertThat(checkout.confirm(session("paid", 1350, StripeService.RESTAURANT_ORDER))).isTrue();

        when(orders.findById(order.id())).thenReturn(Optional.empty());
        assertThat(checkout.confirm(session("paid", 1350, StripeService.RESTAURANT_ORDER))).isFalse();

        verify(orders, never()).markOrderPaid(any());
    }

    @Test
    void theOrderIsLookedUpInItsOwnRestaurantAndTheRequestsRestaurantIsPutBack() {
        TenantContext.setCurrentTenant("pizza");
        when(orders.findById(order.id())).thenAnswer(call -> {
            assertThat(TenantContext.getCurrentTenant()).isEqualTo("kebab");
            return Optional.of(order);
        });

        checkout.confirm(session("paid", 1350, StripeService.RESTAURANT_ORDER));

        assertThat(TenantContext.getCurrentTenant()).isEqualTo("pizza");
    }

    @Test
    void aGuestComingBackCanOnlyConfirmACheckoutOfTheRestaurantTheyAreOn() throws Exception {
        when(stripe.retrieveSession("cs_test_1")).thenReturn(session("paid", 1350, StripeService.RESTAURANT_ORDER));

        TenantContext.setCurrentTenant("pizza");
        assertThat(checkout.confirmReturn("cs_test_1")).isFalse();
        verify(orders, never()).markOrderPaid(any());

        TenantContext.setCurrentTenant("kebab");
        assertThat(checkout.confirmReturn("cs_test_1")).isTrue();
        verify(orders).markOrderPaid(order.id());
    }

    // ---- webhook signatures

    @Test
    void webhookCallsMustCarryStripesSignature() throws Exception {
        String secret = "whsec_unit_test";
        String payload = "{\"id\":\"evt_1\",\"object\":\"event\",\"type\":\"checkout.session.completed\"}";
        long now = System.currentTimeMillis() / 1000;
        String signature = "t=" + now + ",v1=" + Webhook.Util.computeHmacSha256(secret, now + "." + payload);
        StripeService signed = new StripeService("sk_test_unit", secret);

        assertThat(signed.constructEvent(payload, signature).getType()).isEqualTo("checkout.session.completed");
        assertThatThrownBy(() -> signed.constructEvent(payload, null)).isInstanceOf(SignatureVerificationException.class);
        assertThatThrownBy(() -> signed.constructEvent(payload, "")).isInstanceOf(SignatureVerificationException.class);
        assertThatThrownBy(() -> signed.constructEvent(payload.replace("evt_1", "evt_2"), signature))
                .isInstanceOf(SignatureVerificationException.class);
        assertThatThrownBy(() -> new StripeService("sk_test_unit", "").constructEvent(payload, signature))
                .as("no secret configured: refuse rather than trust").isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void amountsAreSentToStripeInCents() {
        assertThat(StripeService.toCents(new BigDecimal("13.50"))).isEqualTo(1350);
        assertThat(StripeService.toCents(new BigDecimal("0.1"))).isEqualTo(10);
        assertThat(StripeService.toCents(new BigDecimal("19.999"))).isEqualTo(2000);
    }

    @Test
    void isAvailableForTenantChecksStripeConfigurationAndAccountPresence() {
        when(stripe.isConfigured()).thenReturn(false);
        assertThat(checkout.isAvailableForTenant("kebab")).isFalse();

        when(stripe.isConfigured()).thenReturn(true);
        // Location has no connected account, fallback to default account which is set in setUp
        assertThat(checkout.isAvailableForTenant("kebab")).isTrue();

        // When neither location account nor default account is set
        OrderCheckoutService unconfiguredDefault = new OrderCheckoutService(stripe, orders, locations, "");
        assertThat(unconfiguredDefault.isAvailableForTenant("kebab")).isFalse();

        when(locations.getLocation("kebab")).thenReturn(Optional.of(
                new TenantLocation("1", "owner", "kebab", "RESTAURANT", null, "acct_kebab")));
        assertThat(unconfiguredDefault.isAvailableForTenant("kebab")).isTrue();
    }
}
