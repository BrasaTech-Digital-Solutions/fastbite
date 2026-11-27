-- Location delete safety: 7-day soft-delete undo window, 90-day production name holds,
-- and linked sandbox hide/restore/erase action retries.

ALTER TABLE public.tenant_locations
    ADD COLUMN IF NOT EXISTS pending_delete_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE public.tenant_locations
    ADD COLUMN IF NOT EXISTS erase_after TIMESTAMP WITH TIME ZONE;

ALTER TABLE public.tenant_locations
    ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(160);

CREATE INDEX IF NOT EXISTS tenant_locations_erase_after_idx
    ON public.tenant_locations (erase_after);

CREATE TABLE IF NOT EXISTS public.location_name_holds (
    slug VARCHAR(128) PRIMARY KEY,
    owner VARCHAR(160) NOT NULL,
    held_until TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS location_name_holds_until_idx
    ON public.location_name_holds (held_until);

ALTER TABLE public.pending_sandbox_deletions
    ADD COLUMN IF NOT EXISTS action VARCHAR(16) NOT NULL DEFAULT 'ERASE';
