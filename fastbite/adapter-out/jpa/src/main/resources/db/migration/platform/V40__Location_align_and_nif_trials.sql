-- Part A: retry queue for sandbox cleanup after production delete/close.
-- Part B: per-NIF free trial clock that survives location and owner account deletion.

CREATE TABLE IF NOT EXISTS public.pending_sandbox_deletions (
    tenant_id VARCHAR(63) PRIMARY KEY,
    owner_username VARCHAR(255) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS pending_sandbox_deletions_due_idx
    ON public.pending_sandbox_deletions (next_attempt_at);

CREATE TABLE IF NOT EXISTS public.nif_trials (
    nif VARCHAR(16) PRIMARY KEY,
    trial_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trial_ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO public.nif_trials (nif, trial_started_at, trial_ends_at, created_at, updated_at)
SELECT UPPER(TRIM(fi.tax_id)) AS nif,
       MIN(tb.trial_started_at) AS trial_started_at,
       MIN(tb.trial_started_at) + INTERVAL '30' DAY AS trial_ends_at,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM public.fiscal_issuers fi
JOIN public.tenant_billing tb ON tb.tenant_id = fi.tenant_id
LEFT JOIN public.owner_onboarding oo ON oo.tenant_id = fi.tenant_id
WHERE tb.trial_started_at IS NOT NULL
  AND (oo.tenant_id IS NULL OR oo.opened = TRUE OR tb.status <> 'LOCAL_TRIAL')
  AND LENGTH(TRIM(fi.tax_id)) > 0
  AND LENGTH(TRIM(fi.tax_id)) <= 16
  AND UPPER(TRIM(fi.tax_id)) NOT IN (SELECT nif FROM public.nif_trials)
GROUP BY UPPER(TRIM(fi.tax_id));
