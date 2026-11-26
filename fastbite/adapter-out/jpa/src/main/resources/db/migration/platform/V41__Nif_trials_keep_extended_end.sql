-- Keep the latest trial_started_at per NIF so admin-extended trials do not lose granted days.
UPDATE public.nif_trials nt
SET trial_started_at = (
        SELECT MAX(tb.trial_started_at)
        FROM public.fiscal_issuers fi
        JOIN public.tenant_billing tb ON tb.tenant_id = fi.tenant_id
        LEFT JOIN public.owner_onboarding oo ON oo.tenant_id = fi.tenant_id
        WHERE UPPER(TRIM(fi.tax_id)) = nt.nif
          AND tb.trial_started_at IS NOT NULL
          AND (oo.tenant_id IS NULL OR oo.opened = TRUE OR tb.status <> 'LOCAL_TRIAL')
    ),
    trial_ends_at = (
        SELECT MAX(tb.trial_started_at) + INTERVAL '30' DAY
        FROM public.fiscal_issuers fi
        JOIN public.tenant_billing tb ON tb.tenant_id = fi.tenant_id
        LEFT JOIN public.owner_onboarding oo ON oo.tenant_id = fi.tenant_id
        WHERE UPPER(TRIM(fi.tax_id)) = nt.nif
          AND tb.trial_started_at IS NOT NULL
          AND (oo.tenant_id IS NULL OR oo.opened = TRUE OR tb.status <> 'LOCAL_TRIAL')
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1
    FROM public.fiscal_issuers fi
    JOIN public.tenant_billing tb ON tb.tenant_id = fi.tenant_id
    LEFT JOIN public.owner_onboarding oo ON oo.tenant_id = fi.tenant_id
    WHERE UPPER(TRIM(fi.tax_id)) = nt.nif
      AND tb.trial_started_at IS NOT NULL
      AND (oo.tenant_id IS NULL OR oo.opened = TRUE OR tb.status <> 'LOCAL_TRIAL')
);
