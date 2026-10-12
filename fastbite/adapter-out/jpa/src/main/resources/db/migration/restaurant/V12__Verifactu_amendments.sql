-- A subsanación is a new billing record of the same invoice, appended to the chain. A ticket can
-- therefore have several records: invoice_ticket_id names the ticket of every record, while
-- ticket_id stays on the ticket's first record only, so each ticket keeps exactly one of those.
ALTER TABLE verifactu_records ADD COLUMN invoice_ticket_id VARCHAR(36) REFERENCES fiscal_tickets (id);
UPDATE verifactu_records SET invoice_ticket_id = ticket_id;
ALTER TABLE verifactu_records ALTER COLUMN invoice_ticket_id SET NOT NULL;
ALTER TABLE verifactu_records ALTER COLUMN ticket_id DROP NOT NULL;

-- The record a subsanación amends (each record is amended at most once), and whether the tax
-- agency had refused the invoice until then (RechazoPrevio X)
ALTER TABLE verifactu_records ADD COLUMN amends_record_id VARCHAR(36) UNIQUE;
ALTER TABLE verifactu_records ADD COLUMN amends_after_rejection BOOLEAN;

CREATE INDEX IF NOT EXISTS verifactu_records_invoice_ticket ON verifactu_records (invoice_ticket_id);
