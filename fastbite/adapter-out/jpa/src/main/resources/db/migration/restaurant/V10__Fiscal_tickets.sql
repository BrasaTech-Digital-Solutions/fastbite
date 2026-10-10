-- Ticket numbering: the last number given out in each series (such as KHC-T26). Numbers are taken
-- inside the transaction that issues the ticket, so they never skip or repeat.
CREATE TABLE IF NOT EXISTS fiscal_series (
    series VARCHAR(40) NOT NULL PRIMARY KEY,
    last_number INT NOT NULL
);

-- Tickets exactly as issued. Rows are only ever inserted: corrections are new tickets.
CREATE TABLE IF NOT EXISTS fiscal_tickets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    kind VARCHAR(20) NOT NULL,
    series VARCHAR(40) NOT NULL,
    ticket_number INT NOT NULL,
    full_number VARCHAR(60) NOT NULL UNIQUE,
    training BOOLEAN NOT NULL,
    -- Local date and time of issue plus its UTC offset, read back the same on every database
    issued_local TIMESTAMP NOT NULL,
    issued_offset VARCHAR(6) NOT NULL,
    issued_by VARCHAR(255),
    issuer_legal_name VARCHAR(120),
    issuer_trade_name VARCHAR(80),
    issuer_tax_id VARCHAR(20),
    issuer_address VARCHAR(160),
    issuer_postal_code VARCHAR(10),
    issuer_city VARCHAR(80),
    issuer_province VARCHAR(80),
    issuer_phone VARCHAR(20),
    recipient_tax_id VARCHAR(20),
    recipient_country VARCHAR(2),
    recipient_name VARCHAR(120),
    recipient_address VARCHAR(200),
    lines TEXT NOT NULL,
    vat_lines TEXT NOT NULL,
    discount DECIMAL(10, 2) NOT NULL,
    total DECIMAL(10, 2) NOT NULL,
    vat_total DECIMAL(10, 2) NOT NULL,
    tip DECIMAL(10, 2) NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    cash_received DECIMAL(10, 2),
    change_given DECIMAL(10, 2),
    corrects_ticket_id VARCHAR(36),
    corrects_full_number VARCHAR(60),
    corrects_issued_on DATE,
    correction_reason VARCHAR(200),
    order_ids TEXT,
    order_numbers VARCHAR(255),
    table_name VARCHAR(255),
    payment_reference VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS fiscal_tickets_issued ON fiscal_tickets (issued_local);
CREATE INDEX IF NOT EXISTS fiscal_tickets_corrects ON fiscal_tickets (corrects_ticket_id);

-- Each order is charged by one ticket at most.
CREATE TABLE IF NOT EXISTS fiscal_sale_orders (
    order_id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL REFERENCES fiscal_tickets (id)
);
