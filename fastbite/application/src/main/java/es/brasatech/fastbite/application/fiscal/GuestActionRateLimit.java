package es.brasatech.fastbite.application.fiscal;

/** Atomic, cross-instance guest-action attempts. Keys must be keyed hashes, never addresses or identifiers. */
public interface GuestActionRateLimit {
    enum Action { INVOICE, EMAIL }

    /** Reserves both ticket and client attempts together; a denied reservation inserts neither. */
    boolean tryAcquire(Action action, String ticketKey, String clientKey);
}
