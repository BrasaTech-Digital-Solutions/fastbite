-- Platform billing configuration (dynamic pricing, trial days, free plan fee)
CREATE TABLE IF NOT EXISTS public.platform_billing_settings (
    id VARCHAR(36) PRIMARY KEY,
    monthly_price_cents BIGINT NOT NULL,
    trial_days INT NOT NULL,
    free_plan_cut_bps INT NOT NULL
);

INSERT INTO public.platform_billing_settings (id, monthly_price_cents, trial_days, free_plan_cut_bps)
SELECT 'default', 4900, 30, 250
WHERE NOT EXISTS (SELECT 1 FROM public.platform_billing_settings WHERE id = 'default');
