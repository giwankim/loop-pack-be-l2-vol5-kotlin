-- Apply to existing MySQL schemas that ran 2026-11-26-payment-status-index.sql, before deploying the in-memory
-- unresolved-payment gauge (spec 281). local/test recreate the schema from JPA; other profiles do not update the schema.
--
-- payment: the gauge no longer counts payments by status, so nothing reads this index, and every payment write still pays for it.
-- InnoDB drops a secondary index in place, without blocking reads or writes.
ALTER TABLE payment DROP INDEX IDX_PAYMENT_ON_STATUS, ALGORITHM = INPLACE, LOCK = NONE;
