-- The end of each record chain (REAL, and TRAINING in the sandbox). Updating the row locks the
-- chain, so records are appended one at a time and each one is chained to the last.
CREATE TABLE IF NOT EXISTS verifactu_chain (
    chain VARCHAR(10) NOT NULL PRIMARY KEY,
    last_record_id VARCHAR(36),
    records INT NOT NULL
);
-- Created up front, so two first records can never race to create their chain
INSERT INTO verifactu_chain (chain, last_record_id, records)
SELECT 'REAL', NULL, 0 WHERE NOT EXISTS (SELECT 1 FROM verifactu_chain WHERE chain = 'REAL');
INSERT INTO verifactu_chain (chain, last_record_id, records)
SELECT 'TRAINING', NULL, 0 WHERE NOT EXISTS (SELECT 1 FROM verifactu_chain WHERE chain = 'TRAINING');

-- One billing record per ticket. Its fiscal content never changes; only the submission columns do.
CREATE TABLE IF NOT EXISTS verifactu_records (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL UNIQUE REFERENCES fiscal_tickets (id),
    chain VARCHAR(10) NOT NULL,
    sequence_number INT NOT NULL,
    invoice_type VARCHAR(2) NOT NULL,
    issuer_nif VARCHAR(20) NOT NULL,
    issuer_name VARCHAR(120),
    invoice_number VARCHAR(60) NOT NULL,
    issue_date DATE NOT NULL,
    generated_local TIMESTAMP NOT NULL,
    generated_offset VARCHAR(6) NOT NULL,
    tax_total DECIMAL(12, 2) NOT NULL,
    amount_total DECIMAL(12, 2) NOT NULL,
    previous_issuer_nif VARCHAR(20),
    previous_invoice_number VARCHAR(60),
    previous_issue_date DATE,
    previous_fingerprint VARCHAR(64),
    fingerprint VARCHAR(64) NOT NULL,
    qr_url VARCHAR(500) NOT NULL,
    corrected_issuer_nif VARCHAR(20),
    corrected_invoice_number VARCHAR(60),
    corrected_issue_date DATE,
    training BOOLEAN NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempts INT NOT NULL,
    last_error VARCHAR(500),
    csv VARCHAR(100),
    submitted_at TIMESTAMP,
    UNIQUE (chain, sequence_number)
);
CREATE INDEX IF NOT EXISTS verifactu_records_status ON verifactu_records (chain, status, sequence_number);
