package es.brasatech.fastbite.application.order;

import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderStatusFlowTest {

    private static CartItem item(String price, int quantity) {
        return new CartItem("line", "product", "Kebab", null, null, quantity, List.of(), new BigDecimal(price));
    }

    private static Order takeaway() {
        return new Order(List.of(item("6.50", 2)), 7, OrderPaymentStatus.UNPAID, OrderChannel.COUNTER, "en",
                "cashier", "Marta", ServiceType.TAKEAWAY);
    }

    @Test
    void anUnpaidOrderMovesForwardAndAwaitsPaymentAtDelivered() {
        List<OrderStatus> visited = new ArrayList<>();
        Order order = takeaway();
        for (int step = 0; step < 7; step++) {
            visited.add(order.status());
            order = order.next();
        }

        assertThat(visited).containsExactly(OrderStatus.CREATED, OrderStatus.ACCEPTED, OrderStatus.PROCESSING,
                OrderStatus.DONE, OrderStatus.DELIVERED, OrderStatus.DELIVERED, OrderStatus.DELIVERED);
    }

    @Test
    void aPaidOrderMovesForwardThroughDoneStraightToComplete() {
        List<OrderStatus> visited = new ArrayList<>();
        Order order = takeaway().markPaid();
        for (int step = 0; step < 7; step++) {
            visited.add(order.status());
            order = order.next();
        }

        assertThat(visited).containsExactly(OrderStatus.ACCEPTED, OrderStatus.PROCESSING, OrderStatus.DONE,
                OrderStatus.COMPLETE, OrderStatus.COMPLETE, OrderStatus.COMPLETE, OrderStatus.COMPLETE);
    }

    @Test
    void anOrderMovesBackOneStepAtATimeAndStopsAtCreated() {
        Order order = takeaway().markPaid().setStatus(OrderStatus.COMPLETE);

        assertThat(order.previous().status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.previous().previous().previous().previous().previous().status()).isEqualTo(OrderStatus.CREATED);
        Order created = takeaway();
        assertThat(created.previous()).isSameAs(created);
    }

    @Test
    void completionRequiresDeliveredAndPaid() {
        Order unpaidDelivered = takeaway().setStatus(OrderStatus.DELIVERED);
        assertThat(unpaidDelivered.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(unpaidDelivered.paymentStatus()).isEqualTo(OrderPaymentStatus.UNPAID);

        // Marking delivered order paid moves it to COMPLETE
        Order paidCompleted = unpaidDelivered.markPaid();
        assertThat(paidCompleted.status()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(paidCompleted.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);

        // Marking a complete order unpaid (ticket cancelled by mistake) reverts to DELIVERED
        Order reverted = paidCompleted.markUnpaid();
        assertThat(reverted.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(reverted.paymentStatus()).isEqualTo(OrderPaymentStatus.UNPAID);

        // Refunding a complete order leaves it COMPLETE
        Order refunded = paidCompleted.markRefunded();
        assertThat(refunded.status()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(refunded.paymentStatus()).isEqualTo(OrderPaymentStatus.REFUNDED);

        // previous() from COMPLETE goes back to DELIVERED even if paid
        Order steppedBack = paidCompleted.previous();
        assertThat(steppedBack.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(steppedBack.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
        assertThat(steppedBack.previous().status()).isEqualTo(OrderStatus.DONE);

        // setStatus(DELIVERED) on paid order completes immediately
        Order paidDone = takeaway().markPaid().setStatus(OrderStatus.DONE);
        assertThat(paidDone.setStatus(OrderStatus.DELIVERED).status()).isEqualTo(OrderStatus.COMPLETE);

        // setStatus(COMPLETE) on unpaid order stays DELIVERED
        Order unpaidDone = takeaway().setStatus(OrderStatus.DONE);
        assertThat(unpaidDone.setStatus(OrderStatus.COMPLETE).status()).isEqualTo(OrderStatus.DELIVERED);

        // Paying at earlier stages never skips kitchen steps
        Order accepted = takeaway().setStatus(OrderStatus.ACCEPTED).markPaid();
        assertThat(accepted.status()).isEqualTo(OrderStatus.ACCEPTED);
        Order processing = takeaway().setStatus(OrderStatus.PROCESSING).markPaid();
        assertThat(processing.status()).isEqualTo(OrderStatus.PROCESSING);

        // Payment expired cancelled order becomes ACCEPTED when paid late by Stripe
        Order expiredCancelled = takeaway().cancel(Order.PAYMENT_EXPIRED_REASON);
        Order restored = expiredCancelled.markPaid();
        assertThat(restored.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(restored.cancelReason()).isNull();
    }

    @Test
    void aCancelledOrderStaysCancelledGoingBackAndIsClosedGoingForward() {
        Order cancelled = takeaway().cancel("No show");

        assertThat(cancelled.cancelReason()).isEqualTo("No show");
        assertThat(cancelled.previous()).isSameAs(cancelled);
        assertThat(cancelled.next().status()).isEqualTo(OrderStatus.COMPLETE);
    }

    @Test
    void everyChangeKeepsWhoTheOrderIsForAndHowItIsServed() {
        Order order = takeaway();
        List<Order> changed = List.of(order.next(), order.cancel("x"), order.setPaymentStatus(OrderPaymentStatus.PAID),
                order.setChannel(OrderChannel.ONLINE), order.withItems(List.of(item("1.00", 1))));

        assertThat(changed).allSatisfy(after -> {
            assertThat(after.id()).isEqualTo(order.id());
            assertThat(after.orderNumber()).isEqualTo(7);
            assertThat(after.customerName()).isEqualTo("Marta");
            assertThat(after.userId()).isEqualTo("cashier");
            assertThat(after.serviceType()).isEqualTo(ServiceType.TAKEAWAY);
        });
    }

    @Test
    void newLinesChangeTheTotalButNotThePaymentOrTheStatus() {
        Order order = takeaway().next();
        assertThat(order.total()).isEqualByComparingTo("13.00");

        Order edited = order.withItems(List.of(item("6.50", 3), item("2.00", 1)));

        assertThat(edited.total()).isEqualByComparingTo("21.50");
        assertThat(edited.subtotal()).isEqualByComparingTo("21.50");
        assertThat(edited.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(edited.paymentStatus()).isEqualTo(OrderPaymentStatus.UNPAID);
    }

    private static Order order(ServiceType serviceType, OrderChannel channel, OrderPaymentStatus payment) {
        return new Order(List.of(item("6.50", 1)), 3, payment, channel, "en", null, "Marta", serviceType);
    }

    @Test
    void onlyAnUnpaidOnlineTakeawayOrderIsHeldBackFromTheKitchen() {
        assertThat(order(ServiceType.TAKEAWAY, OrderChannel.ONLINE, OrderPaymentStatus.UNPAID).heldUntilPaid()).isTrue();

        assertThat(order(ServiceType.TAKEAWAY, OrderChannel.ONLINE, OrderPaymentStatus.PAID).heldUntilPaid()).isFalse();
        assertThat(order(ServiceType.TAKEAWAY, OrderChannel.COUNTER, OrderPaymentStatus.UNPAID).heldUntilPaid())
                .as("pay at pickup, entered by staff").isFalse();
        assertThat(order(ServiceType.DINE_IN, OrderChannel.TABLE, OrderPaymentStatus.UNPAID).heldUntilPaid())
                .as("a table order is prepared and paid afterwards").isFalse();
    }

    @Test
    void staffOnlySeeAnOnlineTakeawayOrderOnceItIsPaid() {
        Order table = order(ServiceType.DINE_IN, OrderChannel.TABLE, OrderPaymentStatus.UNPAID);
        Order pickup = order(ServiceType.TAKEAWAY, OrderChannel.COUNTER, OrderPaymentStatus.UNPAID);
        Order online = order(ServiceType.TAKEAWAY, OrderChannel.ONLINE, OrderPaymentStatus.UNPAID);
        OrderService orders = mock(OrderService.class, CALLS_REAL_METHODS);
        doReturn(List.of(table, pickup, online)).when(orders).findAll();
        doReturn(Optional.of(online)).when(orders).findById(online.id());
        doReturn(Optional.of(online)).when(orders).update(any(), any());
        doNothing().when(orders).publishEvent(any());

        assertThat(orders.getAllOrder()).containsExactly(table, pickup);

        orders.markOrderPaid(online.id());

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders).update(eq(online.id()), saved.capture());
        assertThat(saved.getValue().heldUntilPaid()).isFalse();
        assertThat(saved.getValue().status()).as("enters the kitchen as a confirmed order").isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    void ordersCreatedWithoutAServiceTypeAreTableOrders() {
        Order order = new Order(List.of(), 1, OrderPaymentStatus.UNPAID, OrderChannel.TABLE, "en");

        assertThat(order.serviceType()).isEqualTo(ServiceType.DINE_IN);
    }
}
