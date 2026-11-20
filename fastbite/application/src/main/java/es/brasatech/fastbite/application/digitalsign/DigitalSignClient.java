package es.brasatech.fastbite.application.digitalsign;

/** Outbound port for talking to the Digital Sign REST API. */
public interface DigitalSignClient {

    /** Whether an API token and base URL are configured. */
    boolean isConfigured();

    /** {@code GET /templates/{id}} */
    DigitalSignTemplate getTemplate(String templateId);

    /** {@code POST /requests} with {@code Idempotency-Key}. */
    CreateRequestResult createRequest(CreateRequestCommand command, String idempotencyKey);

    /** {@code GET /requests/{id}} */
    DigitalSignRequest getRequest(String requestId);

    /** {@code GET /requests/{id}} with per-call deadline and retry options. */
    default DigitalSignRequest getRequest(String requestId, CallOptions options) {
        return getRequest(requestId);
    }

    /** {@code POST /requests/{id}/signers/{role}/signing-url} */
    SigningUrlResponse createSigningUrl(String requestId, String role);

    /** {@code POST /requests/{id}/cancel} */
    void cancelRequest(String requestId);

    /** {@code GET /requests/{id}/signed-document} */
    byte[] downloadSignedDocument(String requestId);

    /** {@code GET /requests/{id}/signed-document} with per-call deadline and retry options. */
    default byte[] downloadSignedDocument(String requestId, CallOptions options) {
        return downloadSignedDocument(requestId);
    }

    /** {@code GET /requests/{id}/evidence-report} */
    byte[] downloadEvidenceReport(String requestId);

    /** {@code GET /requests/{id}/evidence-report} with per-call deadline and retry options. */
    default byte[] downloadEvidenceReport(String requestId, CallOptions options) {
        return downloadEvidenceReport(requestId);
    }
}
