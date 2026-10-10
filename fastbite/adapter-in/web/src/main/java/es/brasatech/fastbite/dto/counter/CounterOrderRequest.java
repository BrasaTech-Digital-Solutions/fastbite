package es.brasatech.fastbite.dto.counter;

import es.brasatech.fastbite.domain.order.CartItem;

import java.math.BigDecimal;
import java.util.List;

/**
 * An order taken at the counter.
 *
 * @param paid         charged on the spot: the order is paid and its ticket issued at once
 * @param cashReceived for cash payments, what the guest handed over
 * @param invoice      the customer to name on a full invoice instead of a simplified ticket
 */
public record CounterOrderRequest(
        List<CartItem> items,
        String tableId,
        String paymentMethod,
        boolean paid,
        String customerName,
        BigDecimal cashReceived,
        InvoiceDetails invoice) {

    public CounterOrderRequest(List<CartItem> items, String tableId, String paymentMethod, boolean paid,
            String customerName) {
        this(items, tableId, paymentMethod, paid, customerName, null, null);
    }
}
