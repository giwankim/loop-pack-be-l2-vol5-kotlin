package com.loopers.job.weeklyranking

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.step.ScoreProductReader
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingPublishStore
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingScoreStore
import com.loopers.support.MovableClock
import com.loopers.support.WeeklyRankingInputs
import com.loopers.support.WeeklyRankingJobs.RANKING_TABLES
import com.loopers.support.WeeklyRankingJobs.StoredPublication
import com.loopers.support.WeeklyRankingJobs.StoredRankingItem
import com.loopers.support.WeeklyRankingJobs.StoredScore
import com.loopers.support.WeeklyRankingJobs.insertActivity
import com.loopers.support.WeeklyRankingJobs.insertAssignmentActivities
import com.loopers.support.WeeklyRankingJobs.insertPublication
import com.loopers.support.WeeklyRankingJobs.jobParameters
import com.loopers.support.WeeklyRankingJobs.publications
import com.loopers.support.WeeklyRankingJobs.rankingItems
import com.loopers.support.WeeklyRankingJobs.resultChecksum
import com.loopers.support.WeeklyRankingJobs.scores
import com.loopers.support.WeeklyRankingJobs.snapshotInputs
import com.loopers.support.WeeklyRankingJobs.snapshots
import com.loopers.support.test.BaseBatchJobTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.assertj.core.api.Assertions.tuple
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Shows the assignment's sections 5 and 6 on the real `weeklyRanking` job (specs 246 and 252, 실패와 재시작). A run fails
 * while scoring, keeps the chunks it committed, and the same three values run again continue from the product it stopped at
 * without freezing again. A run fails while publishing and leaves the previous publication showing, and its rerun publishes
 * without freezing or scoring again. The restarted run's ranking and a clean run's both match the assignment's expected one.
 *
 * Revision 1's publication of the period, [PREVIOUS_PUBLICATION], is seeded before each scene, and the scenes run revision
 * 2. [publishStore] is a spy too: a scene makes the publication write throw, or commits the publish and then throws.
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
    transactionManager: PlatformTransactionManager,
) : BaseBatchJobTest() {
    private val requiresNew = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @MockkSpyBean
    private lateinit var scoreStore: WeeklyRankingScoreStore

    @MockkSpyBean
    private lateinit var publishStore: WeeklyRankingPublishStore

    @BeforeEach
    fun setUp() {
        emptyTables("purchase_activity", *RANKING_TABLES)
        jdbcClient.insertPublication(PREVIOUS_PUBLICATION)
        clock.instant = FIRST_RUN_INSTANT
        jobOperatorTestUtils.job = job
    }

    /** The spy throws at the publication write, after the step has inserted the new snapshot's ranking in its transaction. */
    @Test
    fun `a run failing at the publication write ends FAILED with no new ranking and the previous publication kept`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failPublishing()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        verify(exactly = 1) { publishStore.rankScores(SNAPSHOT_ID) }
        assertThat(jdbcClient.rankingItems()).isEmpty()
        assertThat(jdbcClient.publications()).containsExactly(PREVIOUS_PUBLICATION)
    }

    @Test
    fun `the restart of a run failed at the publication write publishes in the same job instance, without freezing or scoring`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failPublishing()
        val failedExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))
        clearMocks(publishStore)
        clock.advance(Duration.ofDays(1))

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jobExecution.jobInstance.id).isEqualTo(failedExecution.jobInstance.id)
        assertThat(jobExecution.id).isNotEqualTo(failedExecution.id)
        assertThat(stepExecutionCounts(jobExecution))
            .containsExactly(
                entry(WeeklyRankingJobConfig.FREEZE_STEP_NAME, 1L),
                entry(WeeklyRankingJobConfig.SCORE_STEP_NAME, 1L),
                entry(WeeklyRankingJobConfig.PUBLISH_STEP_NAME, 2L),
            )
        assertThat(jdbcClient.rankingItems())
            .containsExactly(
                StoredRankingItem(snapshotId = SNAPSHOT_ID, productId = 7, score = 5, productRank = 1),
                StoredRankingItem(snapshotId = SNAPSHOT_ID, productId = 9, score = 5, productRank = 2),
                StoredRankingItem(snapshotId = SNAPSHOT_ID, productId = 8, score = 1, productRank = 3),
            )
        assertThat(jdbcClient.publications())
            .containsExactly(
                StoredPublication(
                    periodStart = LocalDate.parse("2026-08-17"),
                    snapshotId = SNAPSHOT_ID,
                    revision = REVISION,
                    publishedAt = LocalDateTime.ofInstant(FIRST_RUN_INSTANT.plus(Duration.ofDays(1)), ZoneOffset.UTC),
                ),
            )
    }

    /**
     * The assignment's section 5 case: the publish reached the database, but the execution was recorded as failed. The spy
     * commits the step's two writes, the ranking and the publication, each in its own `REQUIRES_NEW` transaction, then throws
     * after the publication, so the step's own transaction rolls back nothing they wrote.
     */
    @Test
    fun `a run whose publish committed under a failed execution ends FAILED with the new snapshot already published`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        commitPublishThenFail()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(jdbcClient.publications())
            .extracting(StoredPublication::snapshotId, StoredPublication::revision)
            .containsExactly(tuple(SNAPSHOT_ID, REVISION))
    }

    /** The clock moves a day between the runs, so a second publication write would show in the published-at time. */
    @Test
    fun `the restart of a run whose publish committed completes and leaves the ranking and the published-at time as they were`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        commitPublishThenFail()
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))
        val rankingAfterFirstRun = jdbcClient.rankingItems()
        val publicationsAfterFirstRun = jdbcClient.publications()
        clearMocks(publishStore)
        clock.advance(Duration.ofDays(1))

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(rankingAfterFirstRun).hasSize(3)
        assertThat(jdbcClient.rankingItems()).isEqualTo(rankingAfterFirstRun)
        assertThat(jdbcClient.publications()).isEqualTo(publicationsAfterFirstRun)
        assertThat(publicationsAfterFirstRun.single().publishedAt)
            .isEqualTo(LocalDateTime.ofInstant(FIRST_RUN_INSTANT, ZoneOffset.UTC))
    }

    /**
     * The assignment's section 6, seventh step, with the clean run below. Each compares its own ranking to the expected
     * text's checksum, so the two runs are shown equal to each other and to the right answer. The cleanup between the tests
     * is the assignment's "isolated apart".
     */
    @Test
    fun `a run restarted after failing before product 9 ranks to the checksum of the assignment's expected ranking`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 9)
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))
        prepareRestart()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.resultChecksum(SNAPSHOT_ID)).isEqualTo(WeeklyRankingInputs.ASSIGNMENT_RESULT_CHECKSUM)
    }

    @Test
    fun `a clean run's ranking matches the checksum of the assignment's expected ranking`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.resultChecksum(SNAPSHOT_ID)).isEqualTo(WeeklyRankingInputs.ASSIGNMENT_RESULT_CHECKSUM)
    }

    @Test
    fun `a run failing before product 9 ends FAILED with scores for 7 and 8, a stored read count of 2 and a frozen snapshot`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 9)

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(jdbcClient.scores(SNAPSHOT_ID))
            .containsExactly(StoredScore(productId = 7, score = 5), StoredScore(productId = 8, score = 1))
        assertThat(storedReadCount(jobExecution)).isEqualTo(2)
        assertThat(jdbcClient.sql("SELECT state FROM weekly_ranking_snapshot").query(String::class.java).list())
            .containsExactly("FROZEN")
    }

    @Test
    fun `the restart with the same three values completes the same job instance in a new execution, scoring product 9 only`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 9)
        val failedExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))
        prepareRestart()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jobExecution.jobInstance.id).isEqualTo(failedExecution.jobInstance.id)
        assertThat(jobExecution.id).isNotEqualTo(failedExecution.id)
        assertThat(jdbcClient.scores(SNAPSHOT_ID))
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
        // the first run's call threw, so the restart summed product 9 once
        verify(exactly = 2) { scoreStore.sumQuantity(SNAPSHOT_ID, 9) }
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
        val failedExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))
        val snapshotAfterFirstRun = jdbcClient.snapshots().single()
        val inputsAfterFirstRun = jdbcClient.snapshotInputs(SNAPSHOT_ID)
        prepareRestart()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(
            jobRepository.getStepExecutionCount(failedExecution.jobInstance, WeeklyRankingJobConfig.FREEZE_STEP_NAME),
        ).isEqualTo(1)
        assertThat(jdbcClient.snapshots().single()).isEqualTo(snapshotAfterFirstRun)
        assertThat(snapshotAfterFirstRun.frozenAt).isEqualTo(LocalDateTime.ofInstant(FIRST_RUN_INSTANT, ZoneOffset.UTC))
        assertThat(jdbcClient.snapshotInputs(SNAPSHOT_ID)).isEqualTo(inputsAfterFirstRun)
    }

    /** The failure is inside the first chunk, so its rollback takes product 7's score and the reader's position with it. */
    @Test
    fun `a run failing at product 8 ends FAILED with no score and no advanced read count`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 8)

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(jdbcClient.scores(SNAPSHOT_ID)).isEmpty()
        assertThat(storedReadCount(jobExecution)).isNull()
    }

    @Test
    fun `the restart of a run failing at product 8 scores all three products`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failSummingProduct(SNAPSHOT_ID, productId = 8)
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))
        prepareRestart()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jobExecution.stepExecutions.single { it.stepName == WeeklyRankingJobConfig.SCORE_STEP_NAME })
            .extracting(StepExecution::getReadCount, StepExecution::getWriteCount)
            .containsExactly(3L, 3L)
        assertThat(jdbcClient.scores(SNAPSHOT_ID))
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
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = SNAPSHOT_ID, revision = REVISION))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(jdbcClient.scores(SNAPSHOT_ID))
            .containsExactly(StoredScore(productId = 7, score = 5), StoredScore(productId = 8, score = 1))
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

    private fun failPublishing() {
        every { publishStore.publish(any(), any()) } throws IllegalStateException("fails writing the publication")
    }

    /**
     * Commits the ranking and the publication in their own `REQUIRES_NEW` transactions, as the real calls would, then fails
     * the step after the publication. The ranking is wrapped too: the publish the assignment's section 5 means is the step's
     * whole transaction, and only committed ranking rows make the rerun meet an already ranked snapshot.
     */
    private fun commitPublishThenFail() {
        every { publishStore.rankScores(SNAPSHOT_ID) } answers {
            requiresNew.executeWithoutResult { callOriginal() }
        }
        every { publishStore.publish(any(), any()) } answers {
            requiresNew.executeWithoutResult { callOriginal() }
            throw IllegalStateException("fails after the publication committed")
        }
    }

    /** How many executions each step has in [jobExecution]'s job instance, read from the job repository. */
    private fun stepExecutionCounts(jobExecution: JobExecution): Map<String, Long> {
        return listOf(
            WeeklyRankingJobConfig.FREEZE_STEP_NAME,
            WeeklyRankingJobConfig.SCORE_STEP_NAME,
            WeeklyRankingJobConfig.PUBLISH_STEP_NAME,
        ).associateWith { stepName ->
            jobRepository.getStepExecutionCount(jobExecution.jobInstance, stepName)
        }
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
        const val SNAPSHOT_ID = "2026-08-17-r2"
        const val REVISION = 2L

        /** Revision 1's publication of the same period, seeded before each scene, for the new snapshot to replace. */
        val PREVIOUS_PUBLICATION = StoredPublication(
            periodStart = LocalDate.parse("2026-08-17"),
            snapshotId = "2026-08-17-r1",
            revision = 1,
            publishedAt = LocalDateTime.parse("2026-08-17T00:05:00"),
        )
        val FIRST_RUN_INSTANT: Instant = Instant.parse("2026-08-24T01:00:00Z")
    }
}
