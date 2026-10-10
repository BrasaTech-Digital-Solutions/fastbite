package es.brasatech.fastbite.domain.fiscal;

import es.brasatech.fastbite.domain.exception.LocalizedRejection;

import java.util.Locale;

/** How a ticket was paid. */
public enum PaymentMethod {
    /** Cash at the counter. */
    CASH,
    /** A card terminal at the counter. */
    CARD,
    /** Paid by the guest online before the order reached the kitchen. */
    ONLINE;

    /** Reads a method sent by the counter; unknown values are refused. */
    public static PaymentMethod of(String value) {
        if (value == null || value.isBlank()) {
            throw new LocalizedRejection("payment.error.method", "Choose how the guest paid");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LocalizedRejection("payment.error.method", "Unknown payment method: " + value);
        }
    }

    public String messageKey() {
        return "ticket.payment." + name().toLowerCase(Locale.ROOT);
    }
}
