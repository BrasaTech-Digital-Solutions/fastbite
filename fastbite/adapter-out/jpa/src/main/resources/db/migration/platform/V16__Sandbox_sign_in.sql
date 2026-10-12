-- Sign-in links from production that the sandbox has accepted, so each opens it only once. A link is
-- good for a minute; rows are forgotten once it has expired.
CREATE TABLE IF NOT EXISTS sandbox_sign_in (
    nonce VARCHAR(64) NOT NULL PRIMARY KEY,
    expires_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS sandbox_sign_in_expires ON sandbox_sign_in (expires_at);
