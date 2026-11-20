-- Per-NIF VERI*FACTU AEAT representation authorisations (Anexo I) and Digital Sign webhook deduplication.
CREATE TABLE public.verifactu_authorizations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    nif VARCHAR(16) NOT NULL,
    legal_name VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL,
    valid_nif VARCHAR(16) UNIQUE,
    active_nif VARCHAR(16) UNIQUE,
    digital_sign_request_id VARCHAR(64) UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    attempt INTEGER NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    signer_name VARCHAR(120),
    certificate_nif VARCHAR(32),
    signed_pdf_path VARCHAR(512),
    signed_pdf_sha256 VARCHAR(64),
    evidence_pdf_path VARCHAR(512),
    evidence_pdf_sha256 VARCHAR(64),
    failure_reason VARCHAR(64),
    created_by VARCHAR(160),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_from TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_verifactu_auth_valid_nif CHECK (
        (status = 'VALID' AND valid_nif IS NOT NULL AND valid_nif = nif)
        OR (status <> 'VALID' AND valid_nif IS NULL)
    ),
    CONSTRAINT chk_verifactu_auth_active_nif CHECK (
        CASE
            WHEN status = 'IN_PROGRESS' THEN active_nif IS NOT NULL AND active_nif = nif
            WHEN status = 'DOWNLOAD_FAILED' THEN active_nif IS NOT NULL AND active_nif = nif
            ELSE active_nif IS NULL
        END
    )
);

CREATE INDEX verifactu_authorizations_nif_idx
    ON public.verifactu_authorizations (nif, created_at DESC);

CREATE INDEX verifactu_authorizations_status_sync_idx
    ON public.verifactu_authorizations (status, last_synced_at);

CREATE TABLE public.digital_sign_webhook_events (
    event_id VARCHAR(128) NOT NULL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    request_id VARCHAR(64),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX digital_sign_webhook_events_received_idx
    ON public.digital_sign_webhook_events (received_at);
