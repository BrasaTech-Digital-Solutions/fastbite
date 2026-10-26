-- Track every Spanish tax ID (NIF/NIE/CIF) registered in FastBite production.
-- Platform-only table; immutable audit record across tenant lifecycles.
CREATE TABLE IF NOT EXISTS public.fiscal_issuers (
    tax_id VARCHAR(30) NOT NULL,
    tenant_id VARCHAR(63) NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tax_id, tenant_id)
);

CREATE INDEX IF NOT EXISTS fiscal_issuers_tax_id_idx ON public.fiscal_issuers (tax_id);
