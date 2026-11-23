-- commerce-streamer's purchase_activity as the weekly ranking's tests need it: the contract's four columns, deleted_at and the
-- confirmed_at index (CONTEXT-MAP.md), plus the id. Test-only: the batch never creates this table, and drift from the real one
-- is caught by week 9's linking test.
CREATE TABLE IF NOT EXISTS purchase_activity (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    deleted_at   DATETIME(6) NULL,
    INDEX IDX_PURCHASE_ACTIVITY_ON_CONFIRMED_AT (confirmed_at)
);
