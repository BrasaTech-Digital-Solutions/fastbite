ALTER TABLE users ADD COLUMN email VARCHAR(254);
ALTER TABLE users ADD COLUMN email_verified_at BIGINT;
ALTER TABLE users ADD COLUMN pending_email VARCHAR(254);
ALTER TABLE users ADD COLUMN account_language VARCHAR(2);
ALTER TABLE users ADD CONSTRAINT restaurant_user_email_normalized CHECK (email IS NULL OR email = LOWER(email));
