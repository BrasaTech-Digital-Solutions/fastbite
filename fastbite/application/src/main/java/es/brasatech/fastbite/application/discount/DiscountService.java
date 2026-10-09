package es.brasatech.fastbite.application.discount;

import es.brasatech.fastbite.domain.discount.DiscountRule;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.order.CartItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface DiscountService {
    List<DiscountRule> findAll();

    Optional<DiscountRule> findById(String id);

    DiscountRule create(DiscountRule rule);

    Optional<DiscountRule> update(String id, DiscountRule rule);

    boolean delete(String id);

    /**
     * Calculates the total discount reduction based on cart items, coupon validation,
     * and automatic discount rules currently active.
     */
    BigDecimal calculateDiscount(List<CartItem> items, String couponCode, String tableId);

    BigDecimal calculateDiscount(List<CartItem> items, String couponCode, String tableId, es.brasatech.fastbite.domain.order.OrderChannel channel);

    /**
     * What a guest's cart comes to.
     *
     * @param tax   the VAT included in the total; menu prices already include it
     * @param vat   the same VAT, rate by rate
     * @param total what the guest pays
     */
    record CartBreakdown(
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal tax,
            BigDecimal total,
            VatBreakdown vat
    ) {}

    default CartBreakdown calculateCartBreakdown(List<CartItem> cartItems, String couponCode, String tableId) {
        var subtotal = cartItems.stream().map(CartItem::totalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = calculateDiscount(cartItems, couponCode, tableId, es.brasatech.fastbite.domain.order.OrderChannel.TABLE);
        BigDecimal discountedSubtotal = subtotal.subtract(discount);
        if (discountedSubtotal.compareTo(BigDecimal.ZERO) < 0) {
            discountedSubtotal = BigDecimal.ZERO;
        }
        BigDecimal total = discountedSubtotal.setScale(2, java.math.RoundingMode.HALF_UP);
        VatBreakdown vat = VatBreakdown.of(cartItems.stream()
                .map(item -> new VatBreakdown.Amount(item.totalPrice(), item.vatRate()))
                .toList(), discount);

        return new CartBreakdown(
                subtotal.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : subtotal,
                discount,
                vat.vat(),
                total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : total,
                vat
        );
    }
}
