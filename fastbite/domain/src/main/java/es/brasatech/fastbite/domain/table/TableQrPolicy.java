package es.brasatech.fastbite.domain.table;

import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderStatus;

public final class TableQrPolicy {

    public static final int DEFAULT_BRAKE_THRESHOLD = 3;
    public static final int DEFAULT_BRAKE_WINDOW_MINUTES = 10;

    public record Decision(OrderStatus status, String approvalNote) {
        public static Decision accepted() {
            return new Decision(OrderStatus.ACCEPTED, null);
        }

        public static Decision supervised() {
            return new Decision(OrderStatus.CREATED, null);
        }

        public static Decision brakeTripped() {
            return new Decision(OrderStatus.CREATED, Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);
        }
    }

    private TableQrPolicy() {}

    /**
     * Determines initial order status for a table QR order.
     * Note: The abuse brake is a soft rate limit (no locking); concurrent orders arriving at the
     * same instant may both pass. It acts as a safety speed bump, not an absolute lock.
     */
    public static Decision initialStatus(Table table, boolean defaultTrusted, long recentQrOrders, int threshold) {
        boolean trusted = table != null ? table.effectiveQrTrusted(defaultTrusted) : defaultTrusted;
        if (!trusted) {
            return Decision.supervised();
        }
        if (recentQrOrders >= threshold) {
            return Decision.brakeTripped();
        }
        return Decision.accepted();
    }

    public static Decision initialStatus(Table table, boolean defaultTrusted, long recentQrOrders) {
        return initialStatus(table, defaultTrusted, recentQrOrders, DEFAULT_BRAKE_THRESHOLD);
    }
}
