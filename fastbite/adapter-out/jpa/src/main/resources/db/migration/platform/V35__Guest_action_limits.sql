-- Guest action attempts are separate from account email and sign-up reservations.
CREATE TABLE public.guest_action_lock (id INTEGER PRIMARY KEY);
INSERT INTO public.guest_action_lock (id) VALUES (1);
CREATE TABLE public.guest_action_limits (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    subject_key VARCHAR(512) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    occurred_at BIGINT NOT NULL
);
CREATE INDEX guest_action_limits_lookup_idx ON public.guest_action_limits (purpose, subject_key, occurred_at);
DELETE FROM public.account_email_limits WHERE purpose IN ('GUEST_INVOICE_TICKET', 'GUEST_INVOICE_CLIENT');
