package com.loopers.job.weeklyranking

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.step.ScoreProductReader
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingScoreStore
import com.loopers.support.WeeklyRankingJobs.insertActivity
import com.loopers.support.WeeklyRankingJobs.insertAssignmentActivities
import com.loopers.support.WeeklyRankingJobs.jobParameters
import com.loopers.support.test.BaseBatchJobTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.step.StepExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.jdbc.Sql
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Shows the assignment's section 6, up to its sixth step, on the real `weeklyRanking` job (spec 246, 실패와 재시작). A run
 * fails while scoring, keeps the chunks it committed, and the same three values run again continue from the product it
 * stopped at without freezing again.
 *
 * The chunk size is 2, so products 7 and 8 are the first chunk and product 9 the second. [scoreStore] is a spy: a scene makes
 * `sumQuantity` throw for one product, and counts how often each product's quantity was summed. Nothing between a first run
 * and its restart empties a table, Batch's metadata included.
 *
 * Between the two runs a scene moves [clock] and records one more purchase of product 9 in the period. Summed over live
 * purchase activity, product 9's score would change; a second freeze would be refused on the existing snapshot ID and fail
 * the restart.
 *
 * No method here returns a `StepExecution`: `@SpringBatchTest`'s step-scope listener would take it for a factory of the step
 * context and call it with no arguments.
 */
@TestPropertySource(
    properties = [
        "spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}",
        "weekly-ranking.score.chunk-size=2",
    ],
)
@Sql("/sql/purchase-activity.sql")
class WeeklyRankingJobRestartTest @Autowired constructor(
    @param:Qualifier(WeeklyRankingJobConfig.JOB_NAME) private val job: Job,
    private val clock: MovableClock,
) : BaseBatchJobTest() {
    @MockkSpyBean
    private lateinit var scoreStore: WeeklyRankingScoreStore

    @BeforeEach
    fun setUp() {
        emptyTables("purchase_activity", "weekly_ranking_score", "weekly_ranking_snapshot_input", "weekly_ranking_snapshot")
        clock.instant = FIRST_RUN_INSTANT
        jobOperatorTestUtils.job = job
    }

    @Test
    fun `a run failing before product 9 ends FAILED with scores for 7 and 8, a stored read count of 2 and a frozen snapshot`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 9)

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(scores()).containsExactly(StoredScore(productId = 7, score = 5), StoredScore(productId = 8, score = 1))
        assertThat(storedReadCount(jobExecution)).isEqualTo(2)
        assertThat(jdbcClient.sql("SELECT state FROM weekly_ranking_snapshot").query(String::class.java).list())
            .containsExactly("FROZEN")
    }

    @Test
    fun `the restart with the same three values completes the same job instance in a new execution, scoring product 9 only`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 9)
        val failedExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))
        prepareRestart()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jobExecution.jobInstance.id).isEqualTo(failedExecution.jobInstance.id)
        assertThat(jobExecution.id).isNotEqualTo(failedExecution.id)
        assertThat(scores())
            .containsExactly(
                StoredScore(productId = 7, score = 5),
                StoredScore(productId = 8, score = 1),
                StoredScore(productId = 9, score = 5),
            )
        assertThat(jobExecution.stepExecutions.single { it.stepName == WeeklyRankingJobConfig.SCORE_STEP_NAME })
            .extracting(StepExecution::getReadCount, StepExecution::getWriteCount)
            .containsExactly(1L, 1L)
        verify(exactly = 1) { scoreStore.sumQuantity(SNAPSHOT_ID, 7) }
        verify(exactly = 1) { scoreStore.sumQuantity(SNAPSHOT_ID, 8) }
    }

    /**
     * The one `freeze` step execution is what shows `freeze` did not run again. A second freeze would not have replaced the
     * snapshot but been refused on its snapshot ID, failing the restart, so the completed restart and the unchanged rows show
     * that neither the moved clock nor the added purchase reached the snapshot by any other way.
     */
    @Test
    fun `the restart does not freeze again, leaving the snapshot and its inputs as the first run froze them`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 9)
        val failedExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))
        val snapshotAfterFirstRun = snapshot()
        val inputsAfterFirstRun = snapshotInputs()
        prepareRestart()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(
            jobRepository.getStepExecutionCount(failedExecution.jobInstance, WeeklyRankingJobConfig.FREEZE_STEP_NAME),
        ).isEqualTo(1)
        assertThat(snapshot()).isEqualTo(snapshotAfterFirstRun)
        assertThat(snapshotAfterFirstRun.frozenAt).isEqualTo(LocalDateTime.ofInstant(FIRST_RUN_INSTANT, ZoneOffset.UTC))
        assertThat(snapshotInputs()).isEqualTo(inputsAfterFirstRun)
    }

    /** The failure is inside the first chunk, so its rollback takes product 7's score and the reader's position with it. */
    @Test
    fun `a run failing at product 8 ends FAILED with no score and no advanced read count`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 8)

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(scores()).isEmpty()
        assertThat(storedReadCount(jobExecution)).isNull()
    }

    @Test
    fun `the restart of a run failing at product 8 scores all three products`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 8)
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))
        prepareRestart()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jobExecution.stepExecutions.single { it.stepName == WeeklyRankingJobConfig.SCORE_STEP_NAME })
            .extracting(StepExecution::getReadCount, StepExecution::getWriteCount)
            .containsExactly(3L, 3L)
        assertThat(scores())
            .containsExactly(
                StoredScore(productId = 7, score = 5),
                StoredScore(productId = 8, score = 1),
                StoredScore(productId = 9, score = 5),
            )
    }

    /**
     * Product 9's score is written and the chunk then fails before it commits. A failure in `sumQuantity` always comes before the
     * chunk's write, so only this scene tells the chunk's transaction from none: without one, the written score would stay
     * while the stored read count says 2.
     */
    @Test
    fun `a run failing after writing product 9's score, before its chunk commits, leaves no score for 9 and a read count of 2`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failAfterWritingProduct(SNAPSHOT_ID, productId = 9)

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(scores()).containsExactly(StoredScore(productId = 7, score = 5), StoredScore(productId = 8, score = 1))
        assertThat(storedReadCount(jobExecution)).isEqualTo(2)
    }

    /**
     * What happens between a failed run and its restart: `sumQuantity` stops failing, keeping the calls the spy recorded; the clock
     * moves a day; and order 45 buys product 9 inside the period, after the freeze.
     */
    private fun prepareRestart() {
        clearMocks(scoreStore, answers = true, recordedCalls = false, verificationMarks = false)
        clock.advance(Duration.ofDays(1))
        jdbcClient.insertActivity(
            orderId = 45,
            productId = 9,
            quantity = 4,
            confirmedAt = LocalDateTime.parse("2026-08-20T09:00:00"),
        )
    }

    private fun failSummingProduct(snapshotId: String, productId: Long) {
        every { scoreStore.sumQuantity(snapshotId, productId) } throws IllegalStateException("fails summing product $productId")
    }

    /** Lets the chunk holding [productId] write its scores, then fails it inside the chunk's transaction. */
    private fun failAfterWritingProduct(snapshotId: String, productId: Long) {
        every { scoreStore.saveScores(snapshotId, match { scores -> scores.any { it.productId == productId } }) } answers {
            callOriginal()
            throw IllegalStateException("fails after writing product $productId")
        }
    }

    /**
     * The reader's `read.count` in the `score` step's `ExecutionContext` as the job repository stored it, read back from the
     * database, or null when the key is absent.
     */
    private fun storedReadCount(jobExecution: JobExecution): Int? {
        val storedStepExecution = jobRepository.getLastStepExecution(
            jobExecution.jobInstance,
            WeeklyRankingJobConfig.SCORE_STEP_NAME,
        )
        val executionContext = requireNotNull(storedStepExecution) { "No score step execution is stored" }.executionContext
        val key = "${ScoreProductReader.NAME}.read.count"

        return if (executionContext.containsKey(key)) executionContext.getInt(key) else null
    }

    private fun scores(): List<StoredScore> {
        return jdbcClient.sql("SELECT product_id, score FROM weekly_ranking_score WHERE snapshot_id = ? ORDER BY product_id")
            .param(SNAPSHOT_ID)
            .query { resultSet, _ ->
                StoredScore(productId = resultSet.getLong("product_id"), score = resultSet.getLong("score"))
            }
            .list()
    }

    private fun snapshot(): StoredSnapshot {
        return jdbcClient.sql("SELECT frozen_at, input_count, input_checksum FROM weekly_ranking_snapshot WHERE snapshot_id = ?")
            .param(SNAPSHOT_ID)
            .query { resultSet, _ ->
                StoredSnapshot(
                    frozenAt = resultSet.getObject("frozen_at", LocalDateTime::class.java),
                    inputCount = resultSet.getLong("input_count"),
                    inputChecksum = resultSet.getString("input_checksum"),
                )
            }
            .single()
    }

    private fun snapshotInputs(): List<StoredSnapshotInput> {
        return jdbcClient
            .sql(
                """
                SELECT order_id, product_id, quantity, confirmed_at FROM weekly_ranking_snapshot_input
                WHERE snapshot_id = ? ORDER BY order_id, product_id
                """.trimIndent(),
            )
            .param(SNAPSHOT_ID)
            .query { resultSet, _ ->
                StoredSnapshotInput(
                    orderId = resultSet.getLong("order_id"),
                    productId = resultSet.getLong("product_id"),
                    quantity = resultSet.getInt("quantity"),
                    confirmedAt = resultSet.getObject("confirmed_at", LocalDateTime::class.java),
                )
            }
            .list()
    }

    private data class StoredSnapshot(val frozenAt: LocalDateTime, val inputCount: Long, val inputChecksum: String)

    private data class StoredSnapshotInput(
        val orderId: Long,
        val productId: Long,
        val quantity: Int,
        val confirmedAt: LocalDateTime,
    )

    private data class StoredScore(val productId: Long, val score: Long)

    /** A clock a scene sets and moves. Only the instant is read: the freeze reads `clock.instant()`. */
    class MovableClock(
        @Volatile var instant: Instant,
    ) : Clock() {
        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }

        override fun instant(): Instant {
            return instant
        }

        override fun getZone(): ZoneId {
            return ZoneOffset.UTC
        }

        override fun withZone(zone: ZoneId): Clock {
            throw UnsupportedOperationException("The movable clock does not change zones")
        }
    }

    /** Puts [MovableClock] in the context as its primary `Clock`. */
    @TestConfiguration(proxyBeanMethods = false)
    class MovableClockConfig {
        @Primary
        @Bean
        fun movableClock(): MovableClock {
            return MovableClock(FIRST_RUN_INSTANT)
        }
    }

    companion object {
        const val SNAPSHOT_ID = "2026-08-17-r1"
        val FIRST_RUN_INSTANT: Instant = Instant.parse("2026-08-24T01:00:00Z")
    }
}
