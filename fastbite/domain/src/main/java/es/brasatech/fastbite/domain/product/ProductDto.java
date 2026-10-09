package es.brasatech.fastbite.domain.product;

import java.math.BigDecimal;
import java.util.Set;

/**
 * DTO for Product management in BackOffice.
 * This differs from the menu Product record as it includes additional fields.
 */
public record ProductDto(
                String id,
                String name,
                BigDecimal price,
                String description,
                String image,
                Set<String> customizations, // Set of customization IDs
                boolean active,
                // Empty: the location's default VAT rate applies
                BigDecimal vatRate) {

        public ProductDto(String id, String name, BigDecimal price, String description, String image,
                        Set<String> customizations, boolean active) {
                this(id, name, price, description, image, customizations, active, null);
        }
}
