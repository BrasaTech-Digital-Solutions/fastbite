-- The AEAT tells billing systems apart by NIF + system + installation number, and keeps one chain
-- per installation. Each chain of a location is therefore its own installation, numbered once and
-- never again. Kept apart from verifactu_chain, whose row is locked while tickets are issued.
CREATE TABLE IF NOT EXISTS verifactu_installations (
    chain VARCHAR(10) NOT NULL PRIMARY KEY,
    installation_number VARCHAR(100) NOT NULL UNIQUE,
    -- IndicadorMultiplesOT: the owner has had more than one location; once true it stays true
    multiple_billings BOOLEAN NOT NULL
);
