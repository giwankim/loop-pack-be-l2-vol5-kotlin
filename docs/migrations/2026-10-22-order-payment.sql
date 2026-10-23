-- Apply to existing MySQL schemas before deploying week 5 step 2, where confirming an order pays through the PG.
-- local/test recreate the schema from JPA; other profiles do not update the schema.
-- Run it over a utf8mb4 connection (mysql --default-character-set=utf8mb4). MySQL stores a check's literals
-- with the connection's character set, and the JPA schema's checks carry utf8mb4.
--
-- orders: two statuses for a payment still running or failed, and the payment method.
-- Every order confirmed before this step was paid with points, so confirmed rows become POINT.
-- The paid amount is now computed from the status and the line items, so paid_amount goes with its check.
-- MySQL refuses to drop a column a check still refers to, so both checks are dropped first.
-- status is a native ENUM in the JPA schema; the new values are appended, so existing rows are untouched.
ALTER TABLE orders MODIFY COLUMN status ENUM ('CONFIRMED', 'DRAFT', 'PAYMENT_FAILED', 'PAYMENT_PENDING') NOT NULL;
ALTER TABLE orders ADD COLUMN payment_method ENUM ('PG', 'POINT') NULL;
UPDATE orders SET payment_method = 'POINT' WHERE status = 'CONFIRMED';
ALTER TABLE orders DROP CHECK CK_ORDERS_ON_PAYMENT_STATE;
ALTER TABLE orders DROP CHECK CK_ORDERS_ON_PAID_AMOUNT_POSITIVE;
ALTER TABLE orders DROP COLUMN paid_amount;
ALTER TABLE orders ADD CONSTRAINT CK_ORDERS_ON_PAYMENT_STATE CHECK (
    (status = 'DRAFT' AND payment_method IS NULL AND confirmed_at IS NULL)
    OR (status IN ('PAYMENT_PENDING', 'PAYMENT_FAILED') AND payment_method IS NOT NULL AND payment_method = 'PG'
        AND confirmed_at IS NULL)
    OR (status = 'CONFIRMED' AND payment_method IS NOT NULL AND confirmed_at IS NOT NULL)
);

-- payment: idempotency keys compare exactly, and each payment mints its own PG order ID (ADR 0025 rule 2, ADR 0029).
-- No payment rows exist in any environment yet, so neither statement meets existing data.
-- The key column took the server's case-insensitive collation, under which the unique key and the lookup ignored case.
-- The PG order ID is no longer derived from the order ID, so its uniqueness no longer follows from UK_PAYMENT_ON_ORDER_ID.
ALTER TABLE payment MODIFY COLUMN idempotency_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE payment ADD CONSTRAINT UK_PAYMENT_ON_PG_ORDER_ID UNIQUE (pg_order_id);
