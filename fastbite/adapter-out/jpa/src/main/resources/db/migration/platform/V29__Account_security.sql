-- Account recovery stores only normalized addresses and token hashes.
ALTER TABLE public.users ADD COLUMN email VARCHAR(254);
ALTER TABLE public.users ADD COLUMN email_verified_at BIGINT;
ALTER TABLE public.users ADD COLUMN pending_email VARCHAR(254);
ALTER TABLE public.users ADD COLUMN account_language VARCHAR(2);
ALTER TABLE public.users ADD CONSTRAINT platform_user_email_normalized CHECK (email IS NULL OR email = LOWER(email));
ALTER TABLE public.users ADD CONSTRAINT platform_user_email_unique UNIQUE (email);

CREATE TABLE public.pending_registrations (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(56) NOT NULL UNIQUE,
    username VARCHAR(100) NOT NULL UNIQUE,
    full_name VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    plan VARCHAR(255) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    language VARCHAR(2) NOT NULL,
    terms_version VARCHAR(100) NOT NULL,
    accepted_at BIGINT NOT NULL,
    marketing BOOLEAN NOT NULL,
    expires_at BIGINT NOT NULL,
    CHECK (email = LOWER(email))
);
CREATE TABLE public.account_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    purpose VARCHAR(32) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(56),
    email VARCHAR(254),
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed_at BIGINT
);
CREATE INDEX account_tokens_subject_idx ON public.account_tokens (subject_type, subject_id, purpose);
CREATE TABLE public.account_email_outbox (
    id VARCHAR(36) PRIMARY KEY,
    purpose VARCHAR(32) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(56),
    language VARCHAR(2) NOT NULL,
    recipient_email VARCHAR(254),
    state VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at BIGINT NOT NULL,
    claim_id VARCHAR(36),
    claimed_at BIGINT,
    created_at BIGINT NOT NULL,
    sent_at BIGINT
);
CREATE INDEX account_email_outbox_ready_idx ON public.account_email_outbox (state, available_at);
CREATE TABLE public.account_email_limits (
    subject_key VARCHAR(512) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    sent_at BIGINT NOT NULL,
    id VARCHAR(36) NOT NULL PRIMARY KEY
);
CREATE INDEX account_email_limits_lookup_idx ON public.account_email_limits (subject_key, purpose, sent_at);
