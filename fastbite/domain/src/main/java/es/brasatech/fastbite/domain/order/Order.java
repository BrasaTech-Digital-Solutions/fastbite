package es.brasatech.fastbite.domain.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record Order(List<CartItem> items, int orderNumber, String id, LocalDateTime createdAt, LocalDateTime updatedAt,
        OrderStatus status, BigDecimal total, String cancelReason, OrderPaymentStatus paymentStatus,
        OrderChannel orderChannel, String orderLanguage, String userId, String customerName, ServiceType serviceType) {

    public static final String PAYMENT_EXPIRED_REASON = "Online payment not received within one hour";
    public static final String NEEDS_APPROVAL_TOO_MANY_ORDERS = "needs-approval:too-many-orders";

    public boolean paymentExpired(LocalDateTime now) {
        return heldUntilPaid() && status == OrderStatus.CREATED && createdAt != null
                && !createdAt.plusHours(1).isAfter(now);
    }

    /** The steps an order moves through from placed to closed. */
    private static final List<OrderStatus> FLOW = List.of(OrderStatus.CREATED, OrderStatus.ACCEPTED,
            OrderStatus.PROCESSING, OrderStatus.DONE, OrderStatus.DELIVERED, OrderStatus.COMPLETE);

    public Order(List<CartItem> cartItems, int orderNumber, OrderStatus initialStatus, String cancelReason,
            OrderPaymentStatus paymentStatus, OrderChannel orderChannel, String orderLanguage, String userId,
            String customerName, ServiceType serviceType) {
        this(cartItems,
             orderNumber,
             UUID.randomUUID().toString(),
             LocalDateTime.now(),
             LocalDateTime.now(),
             initialStatus,
             subtotal(cartItems),
             cancelReason,
             paymentStatus,
             orderChannel,
             orderLanguage,
             userId,
             customerName,
             serviceType);
    }

    /** A new order. */
    public Order(List<CartItem> cartItems, int orderNumber, OrderPaymentStatus paymentStatus, OrderChannel orderChannel,
            String orderLanguage, String userId, String customerName, ServiceType serviceType) {
        this(cartItems, orderNumber, OrderStatus.CREATED, null, paymentStatus, orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    public Order(List<CartItem> cartItems, int orderNumber, OrderPaymentStatus paymentStatus, OrderChannel orderChannel,
            String orderLanguage, String userId, String customerName) {
        this(cartItems, orderNumber, paymentStatus, orderChannel, orderLanguage, userId, customerName, ServiceType.DINE_IN);
    }

    public Order(List<CartItem> cartItems, int orderNumber, OrderPaymentStatus paymentStatus, OrderChannel orderChannel,
            String orderLanguage) {
        this(cartItems, orderNumber, paymentStatus, orderChannel, orderLanguage, null, null);
    }

    public Order next() {
        int step = FLOW.indexOf(status);
        if (step < 0) {
            return setStatus(OrderStatus.COMPLETE);
        }
        if (status == OrderStatus.DELIVERED && paymentStatus != OrderPaymentStatus.PAID) {
            return this;
        }
        return setStatus(FLOW.get(Math.min(step + 1, FLOW.size() - 1)));
    }

    public Order previous() {
        int step = FLOW.indexOf(status);
        if (step <= 0) {
            return this;
        }
        if (status == OrderStatus.COMPLETE) {
            // Staff correcting a mistake: step back to DELIVERED even if paid
            return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), OrderStatus.DELIVERED, total,
                    null, paymentStatus, orderChannel, orderLanguage, userId, customerName, serviceType);
        }
        return setStatus(FLOW.get(step - 1));
    }

    public Order cancel(String cancelReason) {
        return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), OrderStatus.CANCELLED, total,
                cancelReason, paymentStatus, orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    public Order setStatus(OrderStatus status) {
        OrderStatus effective = status;
        if (status == OrderStatus.DELIVERED && paymentStatus == OrderPaymentStatus.PAID) {
            effective = OrderStatus.COMPLETE;
        } else if (this.status != OrderStatus.CANCELLED && status == OrderStatus.COMPLETE && paymentStatus != OrderPaymentStatus.PAID) {
            effective = OrderStatus.DELIVERED;
        }
        return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), effective, total, null, paymentStatus,
                orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    public Order markPaid() {
        if (paymentStatus == OrderPaymentStatus.PAID) {
            return this;
        }
        OrderStatus newStatus = this.status;
        String newCancelReason = this.cancelReason;
        if (status == OrderStatus.CANCELLED && PAYMENT_EXPIRED_REASON.equals(cancelReason)) {
            newStatus = OrderStatus.ACCEPTED;
            newCancelReason = null;
        } else if (status == OrderStatus.CREATED) {
            newStatus = OrderStatus.ACCEPTED;
        } else if (status == OrderStatus.DELIVERED) {
            newStatus = OrderStatus.COMPLETE;
        }
        return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), newStatus, total,
                newCancelReason, OrderPaymentStatus.PAID, orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    public Order markUnpaid() {
        if (paymentStatus == OrderPaymentStatus.UNPAID) {
            return this;
        }
        OrderStatus newStatus = this.status;
        if (status == OrderStatus.COMPLETE) {
            newStatus = OrderStatus.DELIVERED;
        }
        return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), newStatus, total,
                cancelReason, OrderPaymentStatus.UNPAID, orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    public Order markRefunded() {
        return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), status, total,
                cancelReason, OrderPaymentStatus.REFUNDED, orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    public Order setPaymentStatus(OrderPaymentStatus paymentStatus) {
        if (paymentStatus == OrderPaymentStatus.PAID) {
            return markPaid();
        } else if (paymentStatus == OrderPaymentStatus.UNPAID) {
            return markUnpaid();
        } else if (paymentStatus == OrderPaymentStatus.REFUNDED) {
            return markRefunded();
        }
        return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), status, total, null, paymentStatus,
                orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    public Order setChannel(OrderChannel orderChannel) {
        return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), status, total, null, paymentStatus,
                orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    /** The same order with other lines. */
    public Order withItems(List<CartItem> items) {
        return new Order(items, orderNumber, id, createdAt, LocalDateTime.now(), status, subtotal(items), cancelReason,
                paymentStatus, orderChannel, orderLanguage, userId, customerName, serviceType);
    }

    /**
     * A takeaway order placed online has to be paid before anyone prepares it: until then it is
     * kept away from the kitchen and the counter.
     */
    public boolean heldUntilPaid() {
        return serviceType == ServiceType.TAKEAWAY && orderChannel == OrderChannel.ONLINE
                && paymentStatus == OrderPaymentStatus.UNPAID;
    }

    /** What the lines add up to, before discounts. */
    public BigDecimal subtotal() {
        return subtotal(items);
    }

    private static BigDecimal subtotal(List<CartItem> items) {
        return items.stream().map(CartItem::totalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
