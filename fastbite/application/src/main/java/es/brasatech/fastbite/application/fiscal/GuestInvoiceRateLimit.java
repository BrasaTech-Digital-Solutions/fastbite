package es.brasatech.fastbite.application.fiscal;

/** Atomic, cross-instance quota for guest invoice requests; keys are already keyed hashes. */
public interface GuestInvoiceRateLimit {
    boolean tryAcquire(String ticketKey, String clientKey);
}
