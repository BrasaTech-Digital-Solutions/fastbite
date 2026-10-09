package es.brasatech.fastbite.dto.menu;

import es.brasatech.fastbite.domain.allergen.Allergen;

import java.math.BigDecimal;
import java.util.Set;

/** @param allergens what the option adds to the dish; empty when nothing */
public record CustomizationOption(String id, String name, BigDecimal price, boolean isSelectedByDefault, int defaultValue,
        Set<Allergen> allergens) {

    public CustomizationOption(String id, String name, BigDecimal price, boolean isSelectedByDefault, int defaultValue) {
        this(id, name, price, isSelectedByDefault, defaultValue, Set.of());
    }

    public String allergenCodes() {
        return allergens == null ? "" : Allergen.format(allergens);
    }
}
