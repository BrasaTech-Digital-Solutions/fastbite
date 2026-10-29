-- Terminal time is recorded separately from the time an email was sent.
ALTER TABLE public.account_email_outbox ADD COLUMN finished_at BIGINT;
