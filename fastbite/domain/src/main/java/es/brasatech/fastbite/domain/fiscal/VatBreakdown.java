package es.brasatech.fastbite.domain.fiscal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How much of a VAT-included amount is VAT, rate by rate, as a ticket has to show it.
 *
 * <p>Prices include VAT, so the base of each rate is worked out backwards from what the guest
 * pays: base = gross / (1 + rate), VAT = gross - base. Rounding the base and taking the VAT as the
 * difference keeps base + VAT equal to the gross amount to the cent. An order-wide discount is
 * shared out over the rates in proportion to their amounts.
 *
 * @param lines one line per VAT rate, highest rate first
 * @param gross what the guest pays, VAT included
 */
public record VatBreakdown(List<Line> lines, BigDecimal gross, BigDecimal base, BigDecimal vat) {

    /** The amounts charged at one VAT rate. */
    public record Line(BigDecimal rate, BigDecimal base, BigDecimal vat, BigDecimal gross) {
    }

    /** A VAT-included amount and its rate. */
    public record Amount(BigDecimal gross, BigDecimal rate) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static final VatBreakdown EMPTY = new VatBreakdown(List.of(), zero(), zero(), zero());

    public static VatBreakdown of(List<Amount> amounts) {
        return of(amounts, BigDecimal.ZERO);
    }

    /**
     * @param amounts  VAT-included amounts, each with its rate
     * @param discount taken off the whole of them; never more than they add up to
     */
    public static VatBreakdown of(List<Amount> amounts, BigDecimal discount) {
        Map<BigDecimal, BigDecimal> grossByRate = new TreeMap<>(Comparator.reverseOrder());
        for (Amount amount : amounts) {
            if (amount.gross() == null || amount.gross().signum() == 0) {
                continue;
            }
            BigDecimal rate = amount.rate() != null ? amount.rate().setScale(2, RoundingMode.HALF_UP) : VatRates.DEFAULT;
            grossByRate.merge(rate, amount.gross(), BigDecimal::add);
        }
        BigDecimal total = grossByRate.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            return EMPTY;
        }
        BigDecimal cut = discount == null ? BigDecimal.ZERO : discount.max(BigDecimal.ZERO).min(total.abs());
        if (total.signum() < 0) {
            // Refunds are negative throughout; a discount still reduces their size
            cut = cut.negate();
        }
        Map<BigDecimal, BigDecimal> cutByRate = shareOut(cut, grossByRate, total);

        List<Line> lines = new ArrayList<>();
        BigDecimal sumBase = BigDecimal.ZERO;
        BigDecimal sumVat = BigDecimal.ZERO;
        BigDecimal sumGross = BigDecimal.ZERO;
        for (var entry : grossByRate.entrySet()) {
            BigDecimal rate = entry.getKey();
            BigDecimal gross = entry.getValue().subtract(cutByRate.get(rate)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal base = gross.multiply(HUNDRED).divide(HUNDRED.add(rate), 2, RoundingMode.HALF_UP);
            BigDecimal vat = gross.subtract(base);
            lines.add(new Line(rate, base, vat, gross));
            sumBase = sumBase.add(base);
            sumVat = sumVat.add(vat);
            sumGross = sumGross.add(gross);
        }
        return new VatBreakdown(List.copyOf(lines), sumGross, sumBase, sumVat);
    }

    /** Splits the discount in proportion to each rate's share, giving the rounding cents to the largest. */
    private static Map<BigDecimal, BigDecimal> shareOut(BigDecimal cut, Map<BigDecimal, BigDecimal> grossByRate,
            BigDecimal total) {
        Map<BigDecimal, BigDecimal> shares = new TreeMap<>(Comparator.reverseOrder());
        BigDecimal given = BigDecimal.ZERO;
        BigDecimal largestRate = null;
        for (var entry : grossByRate.entrySet()) {
            BigDecimal share = cut.multiply(entry.getValue()).divide(total, 2, RoundingMode.HALF_UP);
            shares.put(entry.getKey(), share);
            given = given.add(share);
            if (largestRate == null || entry.getValue().abs().compareTo(grossByRate.get(largestRate).abs()) > 0) {
                largestRate = entry.getKey();
            }
        }
        shares.merge(largestRate, cut.subtract(given), BigDecimal::add);
        return shares;
    }

    /** True when the amounts carry more than one rate, so the ticket shows a line per rate. */
    public boolean mixedRates() {
        return lines.size() > 1;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
