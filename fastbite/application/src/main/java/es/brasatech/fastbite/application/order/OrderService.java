package es.brasatech.fastbite.application.order;

import es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent;
import es.brasatech.fastbite.domain.event.OrderStatusChangedEvent;
import es.brasatech.fastbite.domain.order.*;
import es.brasatech.fastbite.domain.table.TableStatus;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Transactional here, not only on the implementation: the default methods below read an order and
 * save it again, and each of them has to be one unit of work.
 */
@Transactional
public interface OrderService {

    List<Order> findAll();

    Optional<Order> findById(String id);

    Order create(Order group);

    Optional<Order> update(String id, Order group);

    boolean delete(String id);

    void clear();

    List<Order> findActiveByTableId(String tableId);

    /** Takeaway orders the customer still has to pay for when collecting them. */
    List<Order> findTakeawayAwaitingPayment();

    /** Takeaway orders awaiting pickup, including those already paid online. */
    default List<Order> findTakeawayForCollection() {
        return findTakeawayAwaitingPayment();
    }

    void setTableStatus(String tableId, TableStatus status);

    void publishEvent(Object event);

    Order createOrderForTable(List<CartItem> cartItems, int orderNumber, String tableNumber, String orderLanguage, String customerName);

    default Order createOrder(List<CartItem> cartItems, int orderNumber, OrderPaymentStatus orderPaymentStatus,
            OrderChannel orderChannel, String orderLanguage, String userId, String customerName, ServiceType serviceType) {
        var order = new Order(cartItems, orderNumber, orderPaymentStatus, orderChannel, orderLanguage, userId,
                customerName, serviceType);
        var savedOrder = create(order);
        publishEvent(new OrderStatusChangedEvent(savedOrder));
        return savedOrder;
    }

    default Order createOrder(List<CartItem> cartItems, int orderNumber, OrderStatus initialStatus, String cancelReason,
            OrderPaymentStatus orderPaymentStatus, OrderChannel orderChannel, String orderLanguage, String userId,
            String customerName, ServiceType serviceType) {
        var order = new Order(cartItems, orderNumber, initialStatus, cancelReason, orderPaymentStatus, orderChannel,
                orderLanguage, userId, customerName, serviceType);
        var savedOrder = create(order);
        publishEvent(new OrderStatusChangedEvent(savedOrder));
        return savedOrder;
    }

    default void moveToNextStatus(String id) {
        var order = findById(id).orElseThrow(() -> new RuntimeException("Not Found"));
        var newOrder = order.next();
        update(id, newOrder);
        publishEvent(new OrderStatusChangedEvent(newOrder));
    }

    default void moveToPreviousStatus(String id) {
        var order = findById(id).orElseThrow(() -> new RuntimeException("Not Found"));
        var newOrder = order.previous();
        update(id, newOrder);
        publishEvent(new OrderStatusChangedEvent(newOrder));
    }

    default void batchMoveStatus(List<String> ids, boolean forward) {
        for (String id : ids) {
            if (forward) {
                moveToNextStatus(id);
            } else {
                moveToPreviousStatus(id);
            }
        }
    }

    default void cancelOrder(String id, String cancelReason) {
        var order = findById(id).orElseThrow(() -> new RuntimeException("Not Found"));
        var cancelledOrder = order.cancel(cancelReason);
        update(id, cancelledOrder);
        publishEvent(new OrderStatusChangedEvent(cancelledOrder));
    }

    default void setOrderStatus(String id, OrderStatus orderStatus) {
        var order = findById(id).orElseThrow(() -> new RuntimeException("Not Found"));
        var newOrder = order.setStatus(orderStatus);
        update(id, newOrder);
        publishEvent(new OrderStatusChangedEvent(newOrder));
    }

    default void markOrderPaid(String id) {
        var order = findById(id).orElseThrow(() -> new RuntimeException("Not Found: " + id));
        var paidOrder = order.markPaid();
        update(id, paidOrder);
        publishEvent(new es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent(paidOrder));
        publishEvent(new OrderStatusChangedEvent(paidOrder));
    }

    /** Marks an order's payment as given back after its ticket was refunded. */
    void markOrderRefunded(String id);

    /**
     * Marks an order as cancelled and needing a manual refund (used when an online payment arrives
     * in the race window without VERI*FACTU authorisation and automatic refund could not complete).
     */
    default void markOrderNeedsRefund(String id, String reason) {
        findById(id).ifPresent(order -> {
            var updated = order.markNeedsRefund(reason);
            update(id, updated);
            publishEvent(new OrderPaymentStatusChangedEvent(updated));
            publishEvent(new OrderStatusChangedEvent(updated));
        });
    }

    /** Online orders whose payment was captured while the location could not issue a ticket and still need a refund. */
    default List<Order> findOrdersNeedingRefund() {
        return findAll().stream()
                .filter(order -> order.paymentStatus() == OrderPaymentStatus.NEEDS_REFUND)
                .toList();
    }

    /** Marks an order unpaid again after the ticket that charged it was cancelled as a mistake. */
    void markOrderUnpaid(String id);

    /** The orders staff work with: everything except online orders still waiting to be paid. */
    default List<Order> getAllOrder() {
        return findAll().stream()
                .filter(order -> !order.heldUntilPaid())
                .toList();
    }

}
