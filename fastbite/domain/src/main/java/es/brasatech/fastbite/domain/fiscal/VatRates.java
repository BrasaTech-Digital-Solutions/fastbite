package es.brasatech.fastbite.domain.fiscal;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Spanish VAT (IVA) rates a restaurant can charge. Menu prices always include VAT.
 */
public final class VatRates {

    /** General rate: alcoholic drinks sold for takeaway, merchandise. */
    public static final BigDecimal GENERAL = new BigDecimal("21.00");
    /** Reduced rate: food and drink served in restaurants, bars and cafés, and most takeaway food. */
    public static final BigDecimal REDUCED = new BigDecimal("10.00");
    /** Super-reduced rate: basic foods such as bread, milk, eggs, fruit and vegetables. */
    public static final BigDecimal SUPER_REDUCED = new BigDecimal("4.00");

    /** Restaurants serve under the reduced rate, so a new location starts with it. */
    public static final BigDecimal DEFAULT = REDUCED;

    public static final List<BigDecimal> ALL = List.of(GENERAL, REDUCED, SUPER_REDUCED);

    private VatRates() {
    }

    public static boolean isAllowed(BigDecimal rate) {
        return rate != null && ALL.stream().anyMatch(allowed -> allowed.compareTo(rate) == 0);
    }

    /** The rate in its stored form (two decimals), or an exception if Spain has no such rate. */
    public static BigDecimal require(BigDecimal rate) {
        if (!isAllowed(rate)) {
            throw new IllegalArgumentException("Choose a VAT rate of 21%, 10% or 4%");
        }
        return rate.setScale(2);
    }
}
