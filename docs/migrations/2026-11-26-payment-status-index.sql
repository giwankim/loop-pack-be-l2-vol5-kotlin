-- Apply to existing MySQL schemas before deploying week 10 step 1, where a gauge counts 끝나지 않은 결제 by status on a schedule.
-- local/test recreate the schema from JPA; other profiles do not update the schema.
--
-- payment: the count groups and filters on status only, so this index answers it alone (a covering index range scan).
-- It does not cover deleted_at, and the count deliberately does not filter on it: nothing deletes a payment.
-- InnoDB builds a secondary index in place, without blocking reads or writes.
ALTER TABLE payment ADD INDEX IDX_PAYMENT_ON_STATUS (status), ALGORITHM = INPLACE, LOCK = NONE;
