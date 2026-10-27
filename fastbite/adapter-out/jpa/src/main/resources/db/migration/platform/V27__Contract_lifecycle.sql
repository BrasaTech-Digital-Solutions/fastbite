-- Tracks when a location's orders became paused, which lifecycle notices were emailed,
-- when its contract ended (after 30 days paused), and when its non-fiscal data was deleted (30 days later).
ALTER TABLE public.tenant_billing ADD COLUMN IF NOT EXISTS paused_since TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.tenant_billing ADD COLUMN IF NOT EXISTS pause_notified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.tenant_billing ADD COLUMN IF NOT EXISTS warning_notified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.tenant_billing ADD COLUMN IF NOT EXISTS contract_ended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.tenant_billing ADD COLUMN IF NOT EXISTS closing_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.tenant_billing ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP WITH TIME ZONE;
