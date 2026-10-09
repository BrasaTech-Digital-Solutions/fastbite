-- Who issues this location's tickets, and where. One row per restaurant schema.
CREATE TABLE IF NOT EXISTS fiscal_profile (
    id INT NOT NULL PRIMARY KEY,
    legal_name VARCHAR(120),
    trade_name VARCHAR(80),
    tax_id VARCHAR(20),
    address_line VARCHAR(160),
    postal_code VARCHAR(10),
    city VARCHAR(80),
    province VARCHAR(80),
    phone VARCHAR(20),
    email VARCHAR(254),
    series_prefix VARCHAR(8),
    default_vat_rate DECIMAL(5, 2) NOT NULL,
    updated_at TIMESTAMP
);

-- A product's own VAT rate; empty means the location's default rate.
ALTER TABLE products ADD COLUMN IF NOT EXISTS vat_rate DECIMAL(5, 2);

-- The VAT rate each order line was charged at, kept so later catalog changes do not alter it.
ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS vat_rate DECIMAL(5, 2);
