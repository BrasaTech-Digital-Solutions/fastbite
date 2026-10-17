-- Atomic lease when sending a location to the sandbox, so background worker and owner entry never race.
ALTER TABLE sandbox_provisioning ADD COLUMN IF NOT EXISTS claimed_at BIGINT;
