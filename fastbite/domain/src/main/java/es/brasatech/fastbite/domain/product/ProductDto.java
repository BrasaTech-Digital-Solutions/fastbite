package es.brasatech.fastbite.domain.product;

import es.brasatech.fastbite.domain.allergen.Allergen;

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
                BigDecimal vatRate,
                // Null: not declared yet; empty: contains none of the 14 allergens
                Set<Allergen> allergens) {

        public ProductDto(String id, String name, BigDecimal price, String description, String image,
                        Set<String> customizations, boolean active) {
                this(id, name, price, description, image, customizations, active, null, null);
        }

        public ProductDto(String id, String name, BigDecimal price, String description, String image,
                        Set<String> customizations, boolean active, BigDecimal vatRate) {
                this(id, name, price, description, image, customizations, active, vatRate, null);
        }

        /** True once the restaurant has said which allergens the dish contains, even if none. */
        public boolean allergensDeclared() {
                return allergens != null;
        }
}
