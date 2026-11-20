-- Trial starts when a production location opens; track bounded sync failures on VERI*FACTU authorisations.
ALTER TABLE public.tenant_billing ALTER COLUMN trial_started_at DROP NOT NULL;

ALTER TABLE public.verifactu_authorizations ADD COLUMN sync_failure_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public.verifactu_authorizations ADD COLUMN sync_failure_since TIMESTAMP WITH TIME ZONE;
