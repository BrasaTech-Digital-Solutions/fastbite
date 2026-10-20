package es.brasatech.fastbite.dto.order;

import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DashboardOrder(
        List<CartItem> items,
        int orderNumber,
        String id,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        OrderStatus status,
        BigDecimal total,
        String cancelReason,
        OrderPaymentStatus paymentStatus,
        OrderChannel orderChannel,
        String orderLanguage,
        String userId,
        String customerName,
        ServiceType serviceType,
        String tableId,
        String tableName
) {
    public static DashboardOrder from(Order order, String tableId, String tableName) {
        return new DashboardOrder(
                order.items(),
                order.orderNumber(),
                order.id(),
                order.createdAt(),
                order.updatedAt(),
                order.status(),
                order.total(),
                order.cancelReason(),
                order.paymentStatus(),
                order.orderChannel(),
                order.orderLanguage(),
                order.userId(),
                order.customerName(),
                order.serviceType(),
                tableId,
                tableName
        );
    }
}
