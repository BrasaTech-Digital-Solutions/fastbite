package es.brasatech.fastbite.domain.fiscal;

import es.brasatech.fastbite.domain.exception.LocalizedRejection;

import java.util.Locale;

/**
 * The customer named on a full invoice.
 *
 * @param country ISO country of the tax ID; Spanish NIFs are checked, foreign ones only for their shape
 */
public record TicketRecipient(String taxId, String country, String name, String address) {

    public TicketRecipient {
        country = country == null || country.isBlank() ? "ES" : country.trim().toUpperCase(Locale.ROOT);
        taxId = taxId == null ? null : taxId.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s.\\-/]", "");
        if ("ES".equals(country) && taxId != null) {
            taxId = SpanishTaxId.parse(taxId).map(SpanishTaxId::value).orElse(taxId);
        }
        name = name == null ? null : name.strip();
        address = address == null ? null : address.strip();
    }

    /** Checks the customer can be named on an invoice; throws with a message for the cashier if not. */
    public TicketRecipient validated() {
        if (name == null || name.isBlank()) {
            throw new LocalizedRejection("recipient.error.name", "Enter the customer's name or company name for the invoice");
        }
        if (taxId == null || taxId.isBlank()) {
            throw new LocalizedRejection("recipient.error.taxId", "Enter the customer's tax ID for the invoice");
        }
        if (!country.matches("[A-Z]{2}")) {
            throw new LocalizedRejection("recipient.error.country", "Choose the country of the customer's tax ID");
        }
        if ("ES".equals(country) ? !SpanishTaxId.isValid(taxId) : !taxId.matches("[A-Z0-9]{2,20}")) {
            throw new LocalizedRejection("recipient.error.taxIdInvalid", "The customer's tax ID is not valid");
        }
        if (name.length() > 120 || (address != null && address.length() > 200)) {
            throw new LocalizedRejection("recipient.error.length", "The customer's details are too long for the invoice");
        }
        return this;
    }
}
