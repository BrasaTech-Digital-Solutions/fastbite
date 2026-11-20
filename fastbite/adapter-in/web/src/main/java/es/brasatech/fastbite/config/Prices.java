package es.brasatech.fastbite.config;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Prices as guests read them: euros in the page's language, "1,80 €" in Spanish and "€1.80" in English. The guest
 * pages' scripts format the same way with {@code Intl.NumberFormat(<html lang>, {style: 'currency', currency: 'EUR'})}.
 * Templates reach it as {@code #prices} ({@link PriceDialect}).
 */
public final class Prices {

    private static final Currency EURO = Currency.getInstance("EUR");

    private final NumberFormat format;

    public Prices(Locale locale) {
        // The language only, as the page's lang attribute carries it, so the server and the browser agree
        format = NumberFormat.getCurrencyInstance(Locale.of(locale == null ? "es" : locale.getLanguage()));
        format.setCurrency(EURO);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
    }

    /** The amount as a price, or an empty text for no amount. */
    public String format(Number amount) {
        return amount == null ? "" : format.format(amount);
    }
}
