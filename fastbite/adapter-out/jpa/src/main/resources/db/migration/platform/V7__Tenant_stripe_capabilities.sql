-- Add Stripe Connect verification & capability sync columns
ALTER TABLE public.tenant_locations ADD COLUMN IF NOT EXISTS stripe_charges_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE public.tenant_locations ADD COLUMN IF NOT EXISTS stripe_details_submitted BOOLEAN DEFAULT FALSE;
