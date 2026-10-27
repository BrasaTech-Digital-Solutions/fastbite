-- Proof of what each owner accepted at signup: the terms (with the data processing agreement) and,
-- when they opted in, commercial communications. Insert-only; a withdrawal is recorded, never deleted.
CREATE TABLE IF NOT EXISTS public.legal_acceptances (
    username VARCHAR(100) NOT NULL,
    document VARCHAR(30) NOT NULL,
    version VARCHAR(30) NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    withdrawn_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (username, document, version)
);
