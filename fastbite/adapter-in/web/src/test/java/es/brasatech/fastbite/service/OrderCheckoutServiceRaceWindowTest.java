package es.brasatech.fastbite.service;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.checkout.Session;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationRequiredException;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCheckoutServiceRaceWindowTest {

    private StripeService stripeService;
    private OrderService orderService;
    private TenantLocationService tenantLocationService;
    private FiscalTicketService fiscalTicketService;
    private OrderCheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        stripeService = mock(StripeService.class);
        orderService = mock(OrderService.class);
        tenantLocationService = mock(TenantLocationService.class);
        fiscalTicketService = mock(FiscalTicketService.class);
        checkoutService = new OrderCheckoutService(
                stripeService, orderService, tenantLocationService, fiscalTicketService, "");
        when(stripeService.isConfigured()).thenReturn(true);
    }

    private Order sampleOrder(OrderPaymentStatus paymentStatus) {
        CartItem item = new CartItem("l1", "p1", "Burger", null, null, 1, List.of(),
                new BigDecimal("10.00"), new BigDecimal("10.00"));
        return new Order(List.of(item), 42, paymentStatus, OrderChannel.ONLINE, "es", null, "Ana", ServiceType.TAKEAWAY);
    }

    private Session paidSession(String tenantId, String orderId, String paymentIntentId) {
        Session session = new Session();
        session.setId("cs_test_123");
        session.setPaymentStatus("paid");
        session.setAmountTotal(1000L);
        session.setPaymentIntent(paymentIntentId);
        session.setMetadata(Map.of(
                "type", StripeService.RESTAURANT_ORDER,
                "tenantId", tenantId,
                "orderId", orderId));
        return session;
    }

    @Test
    void capturedPaymentRefusedByVerifactuGateTriggersAutomaticStripeRefundAndCancelsOrder() throws Exception {
        Order order = sampleOrder(OrderPaymentStatus.UNPAID);
        Session session = paidSession("bistro", order.id(), "pi_race_123");
        when(orderService.findById(order.id())).thenReturn(Optional.of(order));
        when(tenantLocationService.getLocation("bistro")).thenReturn(Optional.of(
                new TenantLocation("1", "ana", "bistro", "FREE", null, "acct_123", true, true, null)));
        when(fiscalTicketService.settleOnline(eq(order.id()), any(), eq("pi_race_123")))
                .thenThrow(new VerifactuAuthorizationRequiredException(HoldReason.REVOKED));
        when(stripeService.refundPaymentIntent("pi_race_123", "verifactu-hold-refund-" + order.id()))
                .thenReturn("re_auto_123");

        boolean confirmed = checkoutService.confirm(session);

        assertThat(confirmed).isFalse();
        verify(stripeService).refundPaymentIntent("pi_race_123", "verifactu-hold-refund-" + order.id());
        verify(orderService).cancelOrder(order.id(), Order.VERIFACTU_HOLD_REFUNDED_REASON);
        verify(orderService).markOrderRefunded(order.id());
        verify(orderService, never()).markOrderPaid(anyString());
        verify(orderService, never()).markOrderNeedsRefund(anyString(), anyString());
    }

    @Test
    void capturedPaymentRefusedByVerifactuGateMarksNeedsRefundWhenAutomaticRefundFails() throws Exception {
        Order order = sampleOrder(OrderPaymentStatus.UNPAID);
        Session session = paidSession("bistro", order.id(), "pi_fail_456");
        when(orderService.findById(order.id())).thenReturn(Optional.of(order));
        when(tenantLocationService.getLocation("bistro")).thenReturn(Optional.of(
                new TenantLocation("1", "ana", "bistro", "RESTAURANT", null, null, false, false, null)));
        when(fiscalTicketService.settleOnline(eq(order.id()), any(), eq("pi_fail_456")))
                .thenThrow(new VerifactuAuthorizationRequiredException(HoldReason.NOT_SIGNED));
        when(stripeService.refundPaymentIntent(eq("pi_fail_456"), anyString()))
                .thenThrow(new ApiConnectionException("Stripe unreachable"));

        boolean confirmed = checkoutService.confirm(session);

        assertThat(confirmed).isFalse();
        verify(orderService).markOrderNeedsRefund(order.id(), Order.VERIFACTU_HOLD_NEEDS_REFUND_REASON);
        verify(orderService, never()).markOrderPaid(anyString());
        verify(orderService, never()).markOrderRefunded(anyString());
    }

    @Test
    void alreadyRefundedOrderIsNotRefundedTwiceOnWebhookReplay() throws Exception {
        Order order = sampleOrder(OrderPaymentStatus.REFUNDED);
        Session session = paidSession("bistro", order.id(), "pi_done_789");
        when(orderService.findById(order.id())).thenReturn(Optional.of(order));

        boolean confirmed = checkoutService.confirm(session);

        assertThat(confirmed).isFalse();
        verify(fiscalTicketService, never()).settleOnline(anyString(), any(), anyString());
        verify(stripeService, never()).refundPaymentIntent(anyString(), anyString());
    }

    @Test
    void replayedNeedsRefundOrderNeverCallsSettleOnlineEvenWhenGateIsOpenAgain() throws Exception {
        Order order = sampleOrder(OrderPaymentStatus.NEEDS_REFUND);
        Session session = paidSession("bistro", order.id(), "pi_retry_999");
        when(orderService.findById(order.id())).thenReturn(Optional.of(order));
        when(stripeService.refundPaymentIntent("pi_retry_999", "verifactu-hold-refund-" + order.id()))
                .thenReturn("re_retry_999");

        boolean confirmed = checkoutService.confirm(session);

        assertThat(confirmed).isFalse();
        verify(fiscalTicketService, never()).settleOnline(anyString(), any(), anyString());
        verify(stripeService).refundPaymentIntent("pi_retry_999", "verifactu-hold-refund-" + order.id());
        verify(orderService).cancelOrder(order.id(), Order.VERIFACTU_HOLD_REFUNDED_REASON);
        verify(orderService).markOrderRefunded(order.id());
        verify(orderService, never()).markOrderPaid(anyString());
    }
}
