package es.brasatech.fastbite.domain.fiscal;

import java.util.Locale;

/**
 * The kinds of invoice a location issues, each numbered in its own series.
 */
public enum TicketKind {
    /** A simplified invoice (factura simplificada): the everyday ticket. */
    SIMPLIFIED("T"),
    /** A full invoice (factura completa) with the customer's details, issued when the customer asks at payment. */
    FULL("F"),
    /** A full invoice issued later in place of a simplified one, when the customer asks for it afterwards. */
    REPLACEMENT("F"),
    /** A correction (factura rectificativa) of an earlier ticket, such as a refund. */
    RECTIFYING("R");

    private final String seriesLetter;

    TicketKind(String seriesLetter) {
        this.seriesLetter = seriesLetter;
    }

    /** The letter of the series the kind is numbered in: full invoices and replacements share one. */
    public String seriesLetter() {
        return seriesLetter;
    }

    public String messageKey() {
        return "ticket.kind." + name().toLowerCase(Locale.ROOT);
    }

    /** Whether the ticket names the customer. */
    public boolean namesRecipient() {
        return this == FULL || this == REPLACEMENT;
    }
}
