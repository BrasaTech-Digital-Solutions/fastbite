-- Locations production still has to set up in its sandbox, kept until the sandbox has them. Production
-- never waits for the sandbox: a location is listed when it is created and sent by a background job.
CREATE TABLE IF NOT EXISTS sandbox_provisioning (
    tenant_id VARCHAR(63) NOT NULL PRIMARY KEY,
    owner_username VARCHAR(100) NOT NULL,
    plan VARCHAR(255),
    state VARCHAR(10) NOT NULL,
    attempts INT NOT NULL,
    last_error VARCHAR(500),
    next_attempt_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    done_at BIGINT
);
CREATE INDEX IF NOT EXISTS sandbox_provisioning_due ON sandbox_provisioning (state, next_attempt_at);
