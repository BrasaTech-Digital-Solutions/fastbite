-- Track VERI*FACTU on-hold and opened owner email notifications per location so each state transition notifies once.
ALTER TABLE public.tenant_locations ADD COLUMN verifactu_hold_notified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.tenant_locations ADD COLUMN verifactu_opened_notified_at TIMESTAMP WITH TIME ZONE;
