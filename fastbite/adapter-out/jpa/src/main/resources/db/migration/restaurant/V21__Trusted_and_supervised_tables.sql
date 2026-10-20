-- Restaurant default for QR ordering: false = supervised (cashier approval), true = trusted (straight to kitchen)
ALTER TABLE restaurant_settings ADD COLUMN table_qr_trusted BOOLEAN DEFAULT FALSE NOT NULL;

-- Per-table override for QR ordering (NULL = inherit restaurant default)
ALTER TABLE dining_tables ADD COLUMN qr_trusted BOOLEAN;
ALTER TABLE dining_tables ADD COLUMN qr_trusted_by VARCHAR(255);
ALTER TABLE dining_tables ADD COLUMN qr_trusted_at TIMESTAMP;
