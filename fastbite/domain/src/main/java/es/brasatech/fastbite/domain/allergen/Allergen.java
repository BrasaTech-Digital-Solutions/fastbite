package es.brasatech.fastbite.domain.allergen;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The 14 allergens a restaurant must tell its guests about (EU Regulation 1169/2011, Annex II;
 * in Spain also Real Decreto 126/2015), in the order of the regulation.
 */
public enum Allergen {
    GLUTEN("🌾"),
    CRUSTACEANS("🦐"),
    EGGS("🥚"),
    FISH("🐟"),
    PEANUTS("🥜"),
    SOYBEANS("🫘"),
    MILK("🥛"),
    NUTS("🌰"),
    CELERY("🥬"),
    MUSTARD("🟡"),
    SESAME("⚪"),
    SULPHITES("🍷"),
    LUPIN("🌼"),
    MOLLUSCS("🦪");

    private final String icon;

    Allergen(String icon) {
        this.icon = icon;
    }

    public String icon() {
        return icon;
    }

    /** Key of the allergen's name in the message bundles. */
    public String messageKey() {
        return "allergen." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads allergens as they are stored: codes separated by commas.
     *
     * @return null if nothing was ever declared, an empty set for "none of the 14"; unknown codes are ignored
     */
    public static Set<Allergen> parse(String stored) {
        if (stored == null) {
            return null;
        }
        Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);
        Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .forEach(code -> {
                    try {
                        allergens.add(valueOf(code.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                        // A code from a newer version: keep the rest
                    }
                });
        return allergens;
    }

    /** The stored form of a set of allergens, in the order of the regulation; null stays null. */
    public static String format(Collection<Allergen> allergens) {
        if (allergens == null) {
            return null;
        }
        return allergens.stream().sorted().map(Allergen::name).collect(Collectors.joining(","));
    }

    /** A copy in the order of the regulation; null stays null. */
    public static Set<Allergen> copyOf(Collection<Allergen> allergens) {
        if (allergens == null) {
            return null;
        }
        return allergens.isEmpty() ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(allergens);
    }
}
