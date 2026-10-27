package es.brasatech.fastbite.application.tenant;

import java.time.Instant;

/**
 * Keeps the proof of what an owner accepted: the terms (with the data processing agreement) and, if
 * they opted in, commercial communications. Only ever added to, so the record of an acceptance never
 * changes afterwards.
 */
public interface LegalAcceptancePort {

    /** The terms of service, the data processing agreement that is part of them, and the business-only declaration. */
    String TERMS = "terms";
    /** Consent to receive Brasatech's offers by email (opt-in, LSSI-CE art. 21.1). */
    String MARKETING = "marketing";

    LegalAcceptancePort NONE = (username, document, version, acceptedAt) -> { };

    /** Records that this owner accepted this version of the document, unless it is already recorded. */
    void record(String username, String document, String version, Instant acceptedAt);
}
