-- Keeps the full history of consents and withdrawals even when an owner withdraws and later
-- consents again under the same document version.
CREATE TABLE public.legal_acceptances_v25 (
    id VARCHAR(180) NOT NULL PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    document VARCHAR(30) NOT NULL,
    version VARCHAR(30) NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    withdrawn_at TIMESTAMP WITH TIME ZONE
);

INSERT INTO public.legal_acceptances_v25 (id, username, document, version, accepted_at, withdrawn_at)
SELECT username || ':' || document || ':' || version, username, document, version, accepted_at, withdrawn_at
FROM public.legal_acceptances;

DROP TABLE public.legal_acceptances;

ALTER TABLE public.legal_acceptances_v25 RENAME TO legal_acceptances;

CREATE INDEX idx_legal_acceptances_user_doc ON public.legal_acceptances (username, document, accepted_at);
