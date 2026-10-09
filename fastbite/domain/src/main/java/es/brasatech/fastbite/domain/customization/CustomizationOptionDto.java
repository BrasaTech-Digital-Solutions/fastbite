package es.brasatech.fastbite.domain.customization;

import es.brasatech.fastbite.domain.allergen.Allergen;

import java.math.BigDecimal;
import java.util.Set;

/**
 * DTO for Customization Option in BackOffice.
 */
public record CustomizationOptionDto(
        String id,
        String name,
        BigDecimal price,
        boolean isSelectedByDefault,
        int defaultValue,
        // Allergens the option adds to the dish, such as milk for extra cheese; null or empty adds none
        Set<Allergen> allergens) {

    public CustomizationOptionDto(String id, String name, BigDecimal price, boolean isSelectedByDefault,
            int defaultValue) {
        this(id, name, price, isSelectedByDefault, defaultValue, null);
    }

    /**
     * Constructor with default values
     */
    public CustomizationOptionDto(String id, String name, BigDecimal price) {
        this(id, name, price, false, 1);
    }
}
