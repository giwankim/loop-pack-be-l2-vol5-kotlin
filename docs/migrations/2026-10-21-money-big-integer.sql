-- Apply to existing MySQL schemas before deploying the BigInteger Money mapping.
-- local/test recreate these columns from JPA; other profiles do not update the schema.
-- Existing integer values are preserved. Money arithmetic is arbitrary precision,
-- while persisted values are limited to MySQL's maximum DECIMAL precision of 65 digits.
ALTER TABLE product MODIFY COLUMN price DECIMAL(65, 0) NOT NULL;
ALTER TABLE order_line_item MODIFY COLUMN unit_price DECIMAL(65, 0) NOT NULL;
ALTER TABLE orders MODIFY COLUMN paid_amount DECIMAL(65, 0) NULL;
ALTER TABLE point_account MODIFY COLUMN balance DECIMAL(65, 0) NOT NULL;
ALTER TABLE payment MODIFY COLUMN amount DECIMAL(65, 0) NOT NULL;
