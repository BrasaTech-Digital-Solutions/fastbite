package es.brasatech.fastbite.domain.verifactu;

/** Submission status of a billing record. */
public enum VerifactuStatus {
    /** Generated and kept, not yet accepted by the tax agency. */
    PENDING,
    /** Accepted by the tax agency. */
    ACCEPTED,
    /** Accepted, with warnings the restaurant should look at. */
    ACCEPTED_WITH_ERRORS,
    /** Refused: it has to be corrected and sent again. */
    REJECTED,
    /**
     * Refused or accepted with errors, and since superseded by a later record of the same invoice:
     * its subsanación, or its cancellation.
     */
    AMENDED
}
