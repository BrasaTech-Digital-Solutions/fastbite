-- Atomic claim leases for multi-instance erase and sandbox sync jobs,
-- plus preserving prior Stripe cancel-at-period-end state across hide/undo.

ALTER TABLE public.tenant_locations
    ADD COLUMN IF NOT EXISTS erase_claimed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE public.tenant_locations
    ADD COLUMN IF NOT EXISTS stripe_was_canceling BOOLEAN;

ALTER TABLE public.pending_sandbox_deletions
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP WITH TIME ZONE;
