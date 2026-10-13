-- The cash the drawer starts each day with, set by the owner and offered when the cash is checked.
ALTER TABLE restaurant_settings ADD COLUMN cash_float DECIMAL(10, 2) DEFAULT 0 NOT NULL;
