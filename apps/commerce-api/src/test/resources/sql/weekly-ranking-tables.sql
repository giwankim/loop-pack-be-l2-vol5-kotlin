-- The three weekly ranking tables commerce-api reads, with only the columns of the contract in CONTEXT-MAP.md (spec 252).
-- The source is commerce-batch's apps/commerce-batch/src/main/resources/sql/weekly-ranking-schema.sql, which creates them with
-- every column; this copy does not follow a change there by itself. The two apps meet on one database in the fourth branch's
-- linking test, which runs the source file and never this copy.
--
-- Run once before each test class. It drops the tables and creates them again rather than creating what is missing and
-- emptying the rows: a class that runs the real batch (CommerceBatchProcess) leaves the batch's tables behind, whose extra
-- NOT NULL columns would refuse this copy's rows. Dropping makes a class start from the copy, whatever ran before it (spec 259).

DROP TABLE IF EXISTS weekly_ranking_publication;
DROP TABLE IF EXISTS weekly_ranking_item;
DROP TABLE IF EXISTS weekly_ranking_snapshot;

CREATE TABLE weekly_ranking_snapshot (
    snapshot_id  VARCHAR(100) NOT NULL,
    period_start DATE         NOT NULL,
    frozen_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (snapshot_id)
);

CREATE TABLE weekly_ranking_item (
    snapshot_id  VARCHAR(100) NOT NULL,
    product_id   BIGINT       NOT NULL,
    score        BIGINT       NOT NULL,
    product_rank INT          NOT NULL,
    PRIMARY KEY (snapshot_id, product_rank)
);

CREATE TABLE weekly_ranking_publication (
    period_start DATE         NOT NULL,
    snapshot_id  VARCHAR(100) NOT NULL,
    PRIMARY KEY (period_start)
);
