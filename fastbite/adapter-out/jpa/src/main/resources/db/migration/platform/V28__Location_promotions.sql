-- Tracks one-click sandbox promotions to production, single-use preview tokens,
-- and the pre-promotion snapshot bundles kept for undo.
CREATE TABLE IF NOT EXISTS public.location_promotions (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(63) NOT NULL,
    owner_username VARCHAR(100) NOT NULL,
    state VARCHAR(20) NOT NULL,
    token_hash VARCHAR(64) UNIQUE,
    bundle_path VARCHAR(512),
    snapshot_path VARCHAR(512),
    snapshot_name VARCHAR(255),
    summary TEXT,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    applied_at BIGINT,
    undone_at BIGINT
);

CREATE INDEX IF NOT EXISTS location_promotions_tenant_idx ON public.location_promotions (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS public.location_promotion_lease (
    tenant_id VARCHAR(63) PRIMARY KEY,
    claimed_at BIGINT
);
