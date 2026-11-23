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
    PRIMARY KEY (snapshot_id, order_id, product_id),
    -- The score step's product read and per-product sum, answered from the index alone (spec 246). The name shortens the
    -- table to its glossary name, since the full one would pass MySQL's 64 characters. IF NOT EXISTS adds it to no table
    -- that already exists: a local table from before it is dropped by hand.
    INDEX IDX_SNAPSHOT_INPUT_ON_SNAPSHOT_ID_AND_PRODUCT_ID_AND_QUANTITY (snapshot_id, product_id, quantity)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_score (
    snapshot_id VARCHAR(100) NOT NULL,
    product_id  BIGINT       NOT NULL,
    score       BIGINT       NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
);

-- One snapshot's weekly ranking (spec 252). It carries its own copy of the score, so a reader never needs the score table,
-- the job's working table. The primary key makes a read in rank order a key range read. The column is product_rank, since
-- rank is reserved in MySQL 8.
CREATE TABLE IF NOT EXISTS weekly_ranking_item (
    snapshot_id  VARCHAR(100) NOT NULL,
    product_id   BIGINT       NOT NULL,
    score        BIGINT       NOT NULL,
    product_rank INT          NOT NULL,
    PRIMARY KEY (snapshot_id, product_rank),
    CONSTRAINT UK_WEEKLY_RANKING_ITEM_ON_SNAPSHOT_ID_AND_PRODUCT_ID UNIQUE (snapshot_id, product_id)
);

-- What each ranking period shows: one row per period, naming the snapshot whose weekly ranking is visible (spec 252).
CREATE TABLE IF NOT EXISTS weekly_ranking_publication (
    period_start DATE         NOT NULL,
    snapshot_id  VARCHAR(100) NOT NULL,
    revision     BIGINT       NOT NULL,
    published_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (period_start)
);
