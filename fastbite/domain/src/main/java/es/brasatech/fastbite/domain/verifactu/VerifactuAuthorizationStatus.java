package es.brasatech.fastbite.domain.verifactu;

/**
 * Lifecycle states of a restaurant business's (per-NIF) VERI*FACTU AEAT representation
 * authorisation (Anexo I) signed through Digital Sign.
 */
public enum VerifactuAuthorizationStatus {
    /** Signing request created in Digital Sign, awaiting the restaurant's digital certificate signature. */
    IN_PROGRESS,
    /** Request completed with a matching certificate NIF, but downloading/storing one or both PDFs failed. */
    DOWNLOAD_FAILED,
    /** Completed, but Digital Sign already purged the documents (HTTP 410 Gone); owner must sign again. */
    DOCUMENTS_GONE,
    /** Completed, certificate NIF verified against the location's NIF, and both PDFs stored privately. */
    VALID,
    /** Signed with a certificate whose Spanish NIF(s) do not match the business's NIF. */
    NIF_MISMATCH,
    /** Signed with a certificate from which no valid Spanish NIF could be extracted (fail closed). */
    NIF_UNVERIFIED,
    /** Signed or created in Digital Sign's test environment; never valid in production. */
    TEST_ENVIRONMENT,
    /** Signer declined the request in Digital Sign. */
    DECLINED,
    /** Signing request expired in Digital Sign. */
    EXPIRED,
    /** Signing request was cancelled in Digital Sign. */
    CANCELLED,
    /** Previously VALID authorisation that was later revoked (model-ready for future revocation flow). */
    REVOKED;

    public boolean isActive() {
        return this == IN_PROGRESS || this == DOWNLOAD_FAILED;
    }

    public boolean isValid() {
        return this == VALID;
    }

    public boolean isTerminal() {
        return !isActive() && !isValid();
    }
}
