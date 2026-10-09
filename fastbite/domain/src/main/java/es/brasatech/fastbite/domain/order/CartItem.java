package es.brasatech.fastbite.domain.order;

import es.brasatech.fastbite.domain.product.ProductCustomizer;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

public record CartItem(
        String id,
        String itemId,
        String name,
        String description,
        String image,
        int quantity,
        List<ProductCustomizer> customizations,
        BigDecimal price,
        /** The VAT rate this line is charged at; empty on lines from before VAT rates were kept. */
        BigDecimal vatRate) implements Serializable {

    public CartItem(String id, String itemId, String name, String description, String image, int quantity,
            List<ProductCustomizer> customizations, BigDecimal price) {
        this(id, itemId, name, description, image, quantity, customizations, price, null);
    }

    /** The same line charged at the given VAT rate. */
    public CartItem withVatRate(BigDecimal rate) {
        return new CartItem(id, itemId, name, description, image, quantity, customizations, price, rate);
    }

    public BigDecimal totalPrice() {
        BigDecimal unitPrice = price != null ? price : BigDecimal.ZERO;
        if (customizations != null && !customizations.isEmpty()) {
            unitPrice = customizations.stream()
                    .map(ProductCustomizer::price)
                    .reduce(unitPrice, BigDecimal::add);
        }
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
