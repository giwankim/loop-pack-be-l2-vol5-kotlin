package com.loopers.support

import org.springframework.core.io.FileSystemResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import javax.sql.DataSource

/**
 * The tables a scene that runs the real commerce-batch ([CommerceBatchProcess]) starts from, which commerce-api's
 * `DatabaseCleanUp` doesn't know: it empties only commerce-api's entity tables.
 *
 * [reset] drops the weekly ranking's five tables and Batch's metadata, then runs the batch's own `weekly-ranking-schema.sql`, so
 * the ranking's tables are the batch's, with every column, whatever an earlier class left. Batch's metadata is dropped rather
 * than emptied: a `COMPLETED` job instance an earlier class left with the same three parameters would refuse the run, and the
 * batch's `test` profile creates the metadata tables again at its start. The contract-only copy (`sql/weekly-ranking-tables.sql`)
 * is not used here; the classes that use it drop these tables in turn.
 */
object WeeklyRankingBatchTables {
    /** The weekly ranking's tables, as the batch's schema file creates them. */
    private val RANKING_TABLES = listOf(
        "weekly_ranking_publication",
        "weekly_ranking_item",
        "weekly_ranking_score",
        "weekly_ranking_snapshot_input",
        "weekly_ranking_snapshot",
    )

    /** Batch 6's MySQL metadata tables, children first, so no foreign key refuses a drop. */
    private val BATCH_METADATA_TABLES = listOf(
        "BATCH_STEP_EXECUTION_CONTEXT",
        "BATCH_STEP_EXECUTION",
        "BATCH_JOB_EXECUTION_CONTEXT",
        "BATCH_JOB_EXECUTION_PARAMS",
        "BATCH_JOB_EXECUTION",
        "BATCH_JOB_INSTANCE",
        "BATCH_STEP_EXECUTION_SEQ",
        "BATCH_JOB_EXECUTION_SEQ",
        "BATCH_JOB_INSTANCE_SEQ",
    )

    fun reset(dataSource: DataSource) {
        val jdbc = JdbcTemplate(dataSource)
        (RANKING_TABLES + BATCH_METADATA_TABLES).forEach { table ->
            jdbc.execute("DROP TABLE IF EXISTS `$table`")
        }

        ResourceDatabasePopulator(FileSystemResource(CommerceBatchProcess.weeklyRankingSchema())).execute(dataSource)
    }
}
