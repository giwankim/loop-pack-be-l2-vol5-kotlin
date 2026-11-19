-- The three weekly ranking tables commerce-api reads, with only the columns of the contract in CONTEXT-MAP.md (spec 252).
-- The source is commerce-batch's apps/commerce-batch/src/main/resources/sql/weekly-ranking-schema.sql, which creates them with
-- every column; this copy does not follow a change there by itself. The two apps first meet on one database in the fourth
-- branch's linking test.
--
-- Run once before each test class: it creates what is missing and empties the rows, so a class starts from no ranking.

CREATE TABLE IF NOT EXISTS weekly_ranking_snapshot (
    snapshot_id  VARCHAR(100) NOT NULL,
    period_start DATE         NOT NULL,
    frozen_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (snapshot_id)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_item (
    snapshot_id  VARCHAR(100) NOT NULL,
    product_id   BIGINT       NOT NULL,
    score        BIGINT       NOT NULL,
    product_rank INT          NOT NULL,
    PRIMARY KEY (snapshot_id, product_rank)
);

CREATE TABLE IF NOT EXISTS weekly_ranking_publication (
    period_start DATE         NOT NULL,
    snapshot_id  VARCHAR(100) NOT NULL,
    PRIMARY KEY (period_start)
);

DELETE FROM weekly_ranking_publication;
DELETE FROM weekly_ranking_item;
DELETE FROM weekly_ranking_snapshot;
