-- The weekly ranking's tables (spec 237). spring.sql.init runs this file on every startup in the local and test profiles,
-- so it creates only what is missing and never drops: a restarted job finds the rows its first run left. Other profiles
-- apply it by hand, as they do Batch's metadata schema.

CREATE TABLE IF NOT EXISTS weekly_ranking_snapshot (
    snapshot_id    VARCHAR(100) NOT NULL,
    period_start   DATE         NOT NULL,
    revision       BIGINT       NOT NULL,
    state          VARCHAR(20)  NOT NULL,
    frozen_at      DATETIME(6)  NOT NULL,
    input_count    BIGINT       NOT NULL,
    input_checksum CHAR(64)     NOT NULL,
    PRIMARY KEY (snapshot_id),
    CONSTRAINT UK_WEEKLY_RANKING_SNAPSHOT_ON_PERIOD_START_AND_REVISION UNIQUE (period_start, revision)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_snapshot_input (
    snapshot_id  VARCHAR(100) NOT NULL,
    order_id     BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    quantity     INT          NOT NULL,
    confirmed_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id)
);
