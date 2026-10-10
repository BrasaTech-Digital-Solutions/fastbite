package es.brasatech.fastbite.dto.counter;

import es.brasatech.fastbite.domain.fiscal.TicketRecipient;

/** The customer a cashier names on a full invoice. */
public record InvoiceDetails(String taxId, String country, String name, String address) {

    public TicketRecipient toRecipient() {
        return new TicketRecipient(taxId, country, name, address);
    }
}
