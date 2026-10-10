package es.brasatech.fastbite.domain.fiscal;

import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.product.ProductCustomizer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A line of a ticket, frozen when the ticket is issued.
 *
 * @param unitPrice price of one, options included, VAT included
 * @param total     unitPrice × quantity; negative on refunds
 * @param options   the options chosen, printed under the line
 */
public record TicketLine(String name, int quantity, BigDecimal unitPrice, BigDecimal total, BigDecimal vatRate,
        List<Option> options) {

    /** An option of a line, such as a sauce or an extra. */
    public record Option(String name, BigDecimal price, int quantity) {
    }

    /** The line for an order line, at the VAT rate it was charged at (the restaurant's rate if unknown). */
    public static TicketLine of(CartItem item, BigDecimal defaultVatRate) {
        BigDecimal total = item.totalPrice().setScale(2, RoundingMode.HALF_UP);
        BigDecimal unit = item.quantity() == 0 ? total
                : total.divide(BigDecimal.valueOf(item.quantity()), 2, RoundingMode.HALF_UP);
        List<Option> options = item.customizations() == null ? List.of() : item.customizations().stream()
                .map(TicketLine::option).toList();
        return new TicketLine(item.name(), item.quantity(), unit, total,
                item.vatRate() != null ? item.vatRate() : defaultVatRate, options);
    }

    private static Option option(ProductCustomizer customizer) {
        return new Option(customizer.name(), customizer.price(), customizer.quantity());
    }

    /** The same line with the amounts reversed, for a refund. */
    public TicketLine negated() {
        return new TicketLine(name, -quantity, unitPrice, total.negate(), vatRate, options);
    }
}
