-- A sale ticket issued by mistake, when there was no sale, and cancelled on the day it was issued.
-- The ticket stays as it was issued; its orders are freed from fiscal_sale_orders to be charged again.
CREATE TABLE IF NOT EXISTS fiscal_ticket_cancellations (
    ticket_id VARCHAR(36) NOT NULL PRIMARY KEY REFERENCES fiscal_tickets (id),
    cancelled_local TIMESTAMP NOT NULL,
    cancelled_offset VARCHAR(6) NOT NULL,
    cancelled_by VARCHAR(100),
    reason VARCHAR(200) NOT NULL
);

-- Registros de anulación, and whether the tax agency never had the cancelled invoice (SinRegistroPrevio)
ALTER TABLE verifactu_records ADD COLUMN cancellation BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE verifactu_records ADD COLUMN cancellation_without_record BOOLEAN;
