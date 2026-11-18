package com.loopers.batch.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.SNAPSHOT_ID
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Reads each product of the run's snapshot once, in product ID order: one item is one product to score.
 *
 * Its state is saved, so the reader's `read.count` commits with each chunk. A restart runs the same query again and skips
 * that many rows, which is the same place, because a snapshot's inputs do not change after the freeze.
 */
@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
class ScoreProductReader(
    dataSource: DataSource,
    @Value("#{jobParameters['$SNAPSHOT_ID']}") snapshotId: String,
) : JdbcCursorItemReader<Long>(dataSource, SQL, RowMapper { resultSet, _ -> resultSet.getLong("product_id") }) {
    init {
        setName(NAME)
        setPreparedStatementSetter { statement -> statement.setString(1, snapshotId) }
    }

    companion object {
        /** The prefix of the reader's keys in the step's `ExecutionContext`, as in `scoreProductReader.read.count`. */
        const val NAME = "scoreProductReader"

        private val SQL =
            """
            SELECT DISTINCT product_id
            FROM weekly_ranking_snapshot_input
            WHERE snapshot_id = ?
            ORDER BY product_id
            """.trimIndent()
    }
}
