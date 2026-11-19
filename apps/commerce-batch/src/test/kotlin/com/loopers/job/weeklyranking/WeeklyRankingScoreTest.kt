package com.loopers.job.weeklyranking

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.support.WeeklyRankingJobs.RANKING_TABLES
import com.loopers.support.WeeklyRankingJobs.StoredScore
import com.loopers.support.WeeklyRankingJobs.insertActivity
import com.loopers.support.WeeklyRankingJobs.insertAssignmentActivities
import com.loopers.support.WeeklyRankingJobs.jobParameters
import com.loopers.support.WeeklyRankingJobs.scores
import com.loopers.support.test.BaseBatchJobTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.assertj.core.groups.Tuple
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.step.StepExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.jdbc.Sql
import java.time.LocalDateTime

/**
 * Runs `weeklyRanking`, `freeze` then `score`, and reads the scores it left (spec 246). Purchase activities go in by SQL into
 * the test-only `purchase_activity`.
 *
 * No method here returns a `StepExecution`: `@SpringBatchTest`'s step-scope listener would take it for a factory of the step
 * context and call it with no arguments.
 */
@TestPropertySource(properties = ["spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}"])
@Sql("/sql/purchase-activity.sql")
class WeeklyRankingScoreTest @Autowired constructor(
    @param:Qualifier(WeeklyRankingJobConfig.JOB_NAME) private val job: Job,
) : BaseBatchJobTest() {
    @BeforeEach
    fun setUp() {
        emptyTables("purchase_activity", *RANKING_TABLES)
        jobOperatorTestUtils.job = job
    }

    /** Product 7 is in orders 42 (× 2) and 43 (× 3), so its one score is 5. */
    @Test
    fun `scoring the assignment's week completes with one score per product, the sum of its quantities`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(allScores())
            .containsExactly(
                tuple("2026-08-17-r1", 7L, 5L),
                tuple("2026-08-17-r1", 8L, 1L),
                tuple("2026-08-17-r1", 9L, 5L),
            )
    }

    @Test
    fun `a period with no purchase activity completes with no score`() {
        // arrange
        jdbcClient.insertActivity(
            orderId = 50,
            productId = 7,
            quantity = 1,
            confirmedAt = LocalDateTime.parse("2026-08-12T10:00:00"),
        )

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jobExecution.stepExecutions.single { it.stepName == WeeklyRankingJobConfig.SCORE_STEP_NAME })
            .extracting(StepExecution::getStatus, StepExecution::getReadCount, StepExecution::getWriteCount)
            .containsExactly(BatchStatus.COMPLETED, 0L, 0L)
        assertThat(allScores()).isEmpty()
    }

    /** Tells `score = total` from `score = score + total`: the second would leave 104. */
    @Test
    fun `a wrong score stored beforehand under the snapshot ID is replaced by the computed one`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        insertScore(snapshotId = "2026-08-17-r1", productId = 7, score = 99)

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.scores("2026-08-17-r1"))
            .containsExactly(
                StoredScore(productId = 7, score = 5),
                StoredScore(productId = 8, score = 1),
                StoredScore(productId = 9, score = 5),
            )
    }

    @Test
    fun `scoring the same period again under another snapshot ID and revision leaves each snapshot its own three scores`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r2", revision = 2))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(allScores())
            .containsExactly(
                tuple("2026-08-17-r1", 7L, 5L),
                tuple("2026-08-17-r1", 8L, 1L),
                tuple("2026-08-17-r1", 9L, 5L),
                tuple("2026-08-17-r2", 7L, 5L),
                tuple("2026-08-17-r2", 8L, 1L),
                tuple("2026-08-17-r2", 9L, 5L),
            )
    }

    /** The score step adds no state: the snapshot's completion is in Batch's metadata (spec 246). */
    @Test
    fun `after a completed run the snapshot's state is still FROZEN`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jdbcClient.sql("SELECT state FROM weekly_ranking_snapshot").query(String::class.java).list())
            .containsExactly("FROZEN")
    }

    /**
     * The reader's distinct product IDs and the processor's per-product sum are both answered from this index. Every
     * non-unique index on the table is listed, so a second one or a column out of order would show.
     */
    @Test
    fun `the snapshot input has its secondary index under its name with the three columns in order`() {
        // act
        val indexes = jdbcClient
            .sql(
                "SELECT CONCAT(index_name, ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)) " +
                    "FROM information_schema.statistics WHERE table_schema = DATABASE() " +
                    "AND table_name = 'weekly_ranking_snapshot_input' AND non_unique = 1 GROUP BY index_name",
            )
            .query(String::class.java)
            .list()

        // assert
        assertThat(indexes)
            .containsExactly("IDX_SNAPSHOT_INPUT_ON_SNAPSHOT_ID_AND_PRODUCT_ID_AND_QUANTITY:snapshot_id,product_id,quantity")
    }

    private fun insertScore(snapshotId: String, productId: Long, score: Long) {
        jdbcClient.sql("INSERT INTO weekly_ranking_score (snapshot_id, product_id, score) VALUES (?, ?, ?)")
            .params(snapshotId, productId, score)
            .update()
    }

    private fun allScores(): List<Tuple> {
        return jdbcClient.sql("SELECT snapshot_id, product_id, score FROM weekly_ranking_score ORDER BY snapshot_id, product_id")
            .query { resultSet, _ ->
                tuple(resultSet.getString("snapshot_id"), resultSet.getLong("product_id"), resultSet.getLong("score"))
            }
            .list()
    }
}
