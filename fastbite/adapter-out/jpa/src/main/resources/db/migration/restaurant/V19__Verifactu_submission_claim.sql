-- Atomic lease on a VERI*FACTU chain while its pending records are being submitted to the AEAT.
ALTER TABLE verifactu_chain ADD COLUMN IF NOT EXISTS claimed_at BIGINT;
