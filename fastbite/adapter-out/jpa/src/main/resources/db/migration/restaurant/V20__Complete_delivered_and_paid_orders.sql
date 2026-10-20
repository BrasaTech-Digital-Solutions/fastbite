-- Orders that were delivered and paid are completed.
-- Status ordinals: DELIVERED = 4, COMPLETE = 5
-- Payment status ordinals: PAID = 1
UPDATE orders
SET status = 5
WHERE status = 4 AND payment_status = 1;
