-- Spring Session JDBC Tables for PostgreSQL/H2
CREATE TABLE IF NOT EXISTS SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX IF NOT EXISTS SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

-- Users and Roles
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    tenant_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id VARCHAR(36) NOT NULL,
    role VARCHAR(255) NOT NULL,
    PRIMARY KEY (user_id, role),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tenant_locations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_username VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL UNIQUE,
    plan VARCHAR(255) NOT NULL DEFAULT 'Free Demo',
    custom_domain VARCHAR(255) UNIQUE,
    stripe_account_id VARCHAR(255),
    stripe_charges_enabled BOOLEAN DEFAULT FALSE,
    stripe_details_submitted BOOLEAN DEFAULT FALSE
);

-- Products
CREATE TABLE IF NOT EXISTS products (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    description TEXT,
    image VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS product_customizations (
    product_id VARCHAR(36) NOT NULL,
    customization_id VARCHAR(36) NOT NULL,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
);

-- Groups
CREATE TABLE IF NOT EXISTS groups (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    icon VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS group_products (
    group_id VARCHAR(36) NOT NULL,
    product_id VARCHAR(36) NOT NULL,
    FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
);

-- Customizations
CREATE TABLE IF NOT EXISTS customizations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    usage_count INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS customization_options (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    customization_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    is_selected_by_default BOOLEAN DEFAULT FALSE,
    default_value INT DEFAULT 0,
    option_index INT NOT NULL,
    FOREIGN KEY (customization_id) REFERENCES customizations(id) ON DELETE CASCADE
);

-- Orders
CREATE TABLE IF NOT EXISTS orders (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    order_number INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    status SMALLINT,
    total DECIMAL(10, 2) NOT NULL,
    cancel_reason VARCHAR(255),
    payment_status SMALLINT,
    order_channel SMALLINT,
    order_language VARCHAR(10),
    user_id VARCHAR(255),
    customer_name VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS cart_items (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    item_id VARCHAR(36),
    name VARCHAR(255),
    description TEXT,
    image VARCHAR(255),
    quantity INT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cart_item_customizations (
    cart_item_id VARCHAR(36) NOT NULL,
    id VARCHAR(255),
    name VARCHAR(255),
    price DECIMAL(10, 2),
    quantity INT,
    FOREIGN KEY (cart_item_id) REFERENCES cart_items(id) ON DELETE CASCADE
);

-- Discounts
CREATE TABLE IF NOT EXISTS discount_rules (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    scope VARCHAR(50) NOT NULL,
    type VARCHAR(50) NOT NULL,
    discount_value DECIMAL(10, 2) NOT NULL,
    min_subtotal DECIMAL(10, 2) NOT NULL,
    coupon_code VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    accumulative BOOLEAN NOT NULL DEFAULT FALSE,
    apply_on_counter BOOLEAN NOT NULL DEFAULT FALSE
);

-- Dining Tables
CREATE TABLE IF NOT EXISTS dining_tables (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(255),
    seats INT,
    status VARCHAR(50),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS table_orders (
    table_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(255) NOT NULL,
    FOREIGN KEY (table_id) REFERENCES dining_tables(id) ON DELETE CASCADE
);

-- Payment Configuration
CREATE TABLE IF NOT EXISTS payment_configs (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    active BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS payment_modes (
    config_id VARCHAR(255) NOT NULL,
    mode VARCHAR(255) NOT NULL,
    FOREIGN KEY (config_id) REFERENCES payment_configs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS money_denominations (
    config_id VARCHAR(255) NOT NULL,
    denomination_value DECIMAL(10, 2) NOT NULL,
    image VARCHAR(255),
    type VARCHAR(50) NOT NULL,
    FOREIGN KEY (config_id) REFERENCES payment_configs(id) ON DELETE CASCADE
);

-- Translations of text fields into non-default languages, as JSON: {"es": {"name": "..."}}.
-- ADD COLUMN IF NOT EXISTS also upgrades schemas created before the column existed.
ALTER TABLE groups ADD COLUMN IF NOT EXISTS translations TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS translations TEXT;
ALTER TABLE customizations ADD COLUMN IF NOT EXISTS translations TEXT;
ALTER TABLE customization_options ADD COLUMN IF NOT EXISTS translations TEXT;
ALTER TABLE discount_rules ADD COLUMN IF NOT EXISTS translations TEXT;
ALTER TABLE dining_tables ADD COLUMN IF NOT EXISTS translations TEXT;

-- Order numbers: one row per restaurant schema; numbering restarts at 1 every business day.
CREATE TABLE IF NOT EXISTS order_counter (
    id INT NOT NULL PRIMARY KEY,
    business_day DATE NOT NULL,
    last_number INT NOT NULL
);
INSERT INTO order_counter (id, business_day, last_number)
SELECT 1, CURRENT_DATE, 0 WHERE NOT EXISTS (SELECT 1 FROM order_counter WHERE id = 1);

-- How each order is served (DINE_IN, TAKEAWAY). Empty on orders from before service types existed.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS service_type VARCHAR(20);

-- One row of settings per restaurant schema; a restaurant without a row uses the defaults.
CREATE TABLE IF NOT EXISTS restaurant_settings (
    id INT NOT NULL PRIMARY KEY,
    dine_in BOOLEAN NOT NULL,
    takeaway BOOLEAN NOT NULL,
    kds_yellow_minutes INT NOT NULL,
    kds_red_minutes INT NOT NULL
);

-- Platform-only, deliberately qualified: never included in tenant backup/restore.
CREATE TABLE IF NOT EXISTS public.owner_setup_tokens (
    checkout_id VARCHAR(255) PRIMARY KEY,
    tenant_id VARCHAR(63) NOT NULL UNIQUE,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) UNIQUE,
    expires_at BIGINT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE
);

-- Registration claims survive partial DDL and serialize retries across application instances.
CREATE TABLE IF NOT EXISTS public.tenant_lifecycle (
    tenant_id VARCHAR(56) PRIMARY KEY,
    operation_id VARCHAR(300) NOT NULL UNIQUE,
    owner_username VARCHAR(100) NOT NULL,
    state VARCHAR(30) NOT NULL,
    updated_at BIGINT NOT NULL
);

-- Preserve the identity of existing paid owner setup operations during upgrade.
INSERT INTO public.tenant_lifecycle (tenant_id, operation_id, owner_username, state, updated_at)
SELECT t.tenant_id, 'setup:' || t.checkout_id, t.username,
       CASE WHEN t.completed THEN 'ACTIVE' ELSE 'PROVISIONING' END, 0
FROM public.owner_setup_tokens t
WHERE NOT EXISTS (SELECT 1 FROM public.tenant_lifecycle l WHERE l.tenant_id = t.tenant_id);



