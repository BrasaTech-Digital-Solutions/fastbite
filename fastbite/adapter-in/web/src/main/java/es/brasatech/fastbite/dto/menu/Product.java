package es.brasatech.fastbite.dto.menu;

import es.brasatech.fastbite.domain.allergen.Allergen;

import java.math.BigDecimal;
import java.util.Set;

/**
 * @param allergens null when the restaurant has not declared them yet, empty when the dish has none
 */
public record Product(String id, String name, BigDecimal price, String description, String image, String[] customizations,
        boolean active, Set<Allergen> allergens) {

    public Product(String id, String name, BigDecimal price, String description, String image, String[] customizations) {
        this(id, name, price, description, image, customizations, true, null);
    }

    public Product(String id, String name, BigDecimal price, String description, String image, String[] customizations,
            boolean active) {
        this(id, name, price, description, image, customizations, active, null);
    }

    public String customizationsAsString() {
        if (customizations == null || customizations.length == 0) {
            return "";
        }
        return String.join(",", customizations);
    }

    public boolean allergensDeclared() {
        return allergens != null;
    }

    /** The allergen codes for the menu's filter, separated by commas. */
    public String allergenCodes() {
        return allergens == null ? "" : Allergen.format(allergens);
    }
}
