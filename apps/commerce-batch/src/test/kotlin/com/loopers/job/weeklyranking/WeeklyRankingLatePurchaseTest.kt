package com.loopers.job.weeklyranking

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingPublishStore
import com.loopers.support.MovableClock
import com.loopers.support.WeeklyRankingInputs.ASSIGNMENT_CHECKSUM
import com.loopers.support.WeeklyRankingJobs.RANKING_TABLES
import com.loopers.support.WeeklyRankingJobs.StoredPublication
import com.loopers.support.WeeklyRankingJobs.StoredRankingItem
import com.loopers.support.WeeklyRankingJobs.StoredScore
import com.loopers.support.WeeklyRankingJobs.StoredSnapshot
import com.loopers.support.WeeklyRankingJobs.insertActivity
import com.loopers.support.WeeklyRankingJobs.insertAssignmentActivities
import com.loopers.support.WeeklyRankingJobs.jobParameters
import com.loopers.support.WeeklyRankingJobs.publications
import com.loopers.support.WeeklyRankingJobs.rankingItems
import com.loopers.support.WeeklyRankingJobs.scores
import com.loopers.support.WeeklyRankingJobs.snapshotInputs
import com.loopers.support.WeeklyRankingJobs.snapshots
import com.loopers.support.test.BaseBatchJobTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.clearMocks
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.jdbc.Sql
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

private val log = KotlinLogging.logger {}

/**
 * Runs `weeklyRanking` twice on one ranking period, around a late purchase (`CONTEXT.md` 늦은 구매), and reads what each run
 * left (spec 259). Revision 1 freezes the assignment's section 6 inputs; the late purchase, order 45 (product 8 × 5), is then
 * inserted with a confirmed-at inside the period; revision 2 freezes it under a new snapshot ID.
 *
 * The context's clock is the nested [MovableClockConfig]'s, set before each run, so each snapshot's frozen-at time and the
 * publication's published-at time tell the runs apart.
 */
@TestPropertySource(properties = ["spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}"])
@Sql("/sql/purchase-activity.sql")
class WeeklyRankingLatePurchaseTest @Autowired constructor(
    @param:Qualifier(WeeklyRankingJobConfig.JOB_NAME) private val job: Job,
    private val clock: MovableClock,
) : BaseBatchJobTest() {
    @MockkSpyBean
    private lateinit var publishStore: WeeklyRankingPublishStore

    @BeforeEach
    fun setUp() {
        emptyTables("purchase_activity", *RANKING_TABLES)
        jobOperatorTestUtils.job = job
    }

    @Test
    fun `a late purchase leaves revision 1's snapshot, scores, ranking and publication as they were`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        runToCompletion(REVISION_1, at = REVISION_1_AT)

        // act
        insertLatePurchase()

        // assert
        assertThat(jdbcClient.snapshots()).containsExactly(REVISION_1_SNAPSHOT)
        assertThat(jdbcClient.scores(REVISION_1_ID)).isEqualTo(REVISION_1_SCORES)
        assertThat(jdbcClient.rankingItems()).isEqualTo(REVISION_1_ITEMS)
        assertThat(jdbcClient.publications()).containsExactly(REVISION_1_PUBLICATION)
    }

    /** Product 8 is in orders 42 (× 1) and 45 (× 5), so it scores 6 and comes first. */
    @Test
    fun `a revision 2 run under a new snapshot ID completes with the late purchase in its snapshot, scores and ranking`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        runToCompletion(REVISION_1, at = REVISION_1_AT)
        insertLatePurchase()
        clock.instant = REVISION_2_AT

        // act
        val jobExecution = jobOperatorTestUtils.startJob(REVISION_2)

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.snapshots().single { it.snapshotId == REVISION_2_ID })
            .isEqualTo(
                StoredSnapshot(
                    snapshotId = REVISION_2_ID,
                    periodStart = PERIOD_START,
                    revision = 2,
                    state = "FROZEN",
                    frozenAt = utcWallTime(REVISION_2_AT),
                    inputCount = 5,
                    inputChecksum = LATE_PURCHASE_CHECKSUM,
                ),
            )
        assertThat(jdbcClient.scores(REVISION_2_ID))
            .containsExactly(
                StoredScore(productId = 7, score = 5),
                StoredScore(productId = 8, score = 6),
                StoredScore(productId = 9, score = 5),
            )
        assertThat(jdbcClient.rankingItems().filter { it.snapshotId == REVISION_2_ID })
            .containsExactly(
                StoredRankingItem(snapshotId = REVISION_2_ID, productId = 8, score = 6, productRank = 1),
                StoredRankingItem(snapshotId = REVISION_2_ID, productId = 7, score = 5, productRank = 2),
                StoredRankingItem(snapshotId = REVISION_2_ID, productId = 9, score = 5, productRank = 3),
            )
    }

    @Test
    fun `a revision 2 run moves the publication to revision 2's snapshot and leaves revision 1's rows as they were`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        runToCompletion(REVISION_1, at = REVISION_1_AT)
        insertLatePurchase()
        val inputsBefore = jdbcClient.snapshotInputs(REVISION_1_ID)
        clock.instant = REVISION_2_AT

        // act
        jobOperatorTestUtils.startJob(REVISION_2)

        // assert
        assertThat(jdbcClient.publications()).containsExactly(REVISION_2_PUBLICATION)
        assertThat(jdbcClient.snapshots().single { it.snapshotId == REVISION_1_ID }).isEqualTo(REVISION_1_SNAPSHOT)
        assertThat(jdbcClient.snapshotInputs(REVISION_1_ID)).isEqualTo(inputsBefore)
        assertThat(jdbcClient.scores(REVISION_1_ID)).isEqualTo(REVISION_1_SCORES)
        assertThat(jdbcClient.rankingItems().filter { it.snapshotId == REVISION_1_ID }).isEqualTo(REVISION_1_ITEMS)
        logRevisions()
    }

    /**
     * Batch refuses the launch before `freeze` runs, and a new job instance of the same values would fail at `freeze`'s
     * conflict check, so `publish` does not see revision 1 again on this path. The restart scene below is the path that does.
     */
    @Test
    fun `launching revision 1's three values again is refused and leaves revision 2's publication`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        runToCompletion(REVISION_1, at = REVISION_1_AT)
        insertLatePurchase()
        runToCompletion(REVISION_2, at = REVISION_2_AT)
        val snapshotsBefore = jdbcClient.snapshots()
        val itemsBefore = jdbcClient.rankingItems()
        clock.instant = RERUN_AT

        // act
        assertThrows<JobInstanceAlreadyCompleteException> {
            jobOperatorTestUtils.startJob(REVISION_1)
        }

        // assert
        assertThat(jdbcClient.publications()).containsExactly(REVISION_2_PUBLICATION)
        assertThat(jdbcClient.snapshots()).isEqualTo(snapshotsBefore)
        assertThat(jdbcClient.rankingItems()).isEqualTo(itemsBefore)
    }

    /**
     * The one way revision 1's three values reach `publish` after revision 2 is published: revision 1's publish failed, and
     * its job instance is restarted. Batch skips the completed `freeze` and `score`, and `publish` ranks revision 1 and
     * leaves the publication, which holds a later revision.
     */
    @Test
    fun `restarting revision 1's failed publish after revision 2 is published completes and leaves revision 2's publication`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        every { publishStore.countScores(REVISION_1_ID) } throws IllegalStateException("revision 1's publish fails")
        val failedExecution = jobOperatorTestUtils.startJob(REVISION_1)
        check(failedExecution.status == BatchStatus.FAILED) { "Revision 1's run ended ${failedExecution.status}" }
        clearMocks(publishStore)
        insertLatePurchase()
        runToCompletion(REVISION_2, at = REVISION_2_AT)
        clock.instant = RERUN_AT

        // act
        val jobExecution = jobOperatorTestUtils.startJob(REVISION_1)

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jobExecution.jobInstance.id).isEqualTo(failedExecution.jobInstance.id)
        assertThat(jobExecution.stepExecutions.map { it.stepName }).containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP_NAME)
        assertThat(jdbcClient.rankingItems().filter { it.snapshotId == REVISION_1_ID }).isEqualTo(REVISION_1_ITEMS)
        assertThat(jdbcClient.publications()).containsExactly(REVISION_2_PUBLICATION)
    }

    /** Runs [jobParameters] with the clock at [at], and stops the scene unless the run completes. */
    private fun runToCompletion(jobParameters: JobParameters, at: Instant) {
        clock.instant = at
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters)
        check(jobExecution.status == BatchStatus.COMPLETED) { "The run of $jobParameters ended ${jobExecution.status}" }
    }

    /** Order 45, product 8 × 5, confirmed inside the period and recorded after revision 1 was frozen. */
    private fun insertLatePurchase() {
        jdbcClient.insertActivity(
            orderId = 45,
            productId = 8,
            quantity = 5,
            confirmedAt = LocalDateTime.parse("2026-08-20T09:00:00"),
        )
    }

    /** One line for the writing material (`scripts/scene-lines --app commerce-batch WeeklyRankingLatePurchaseTest`). */
    private fun logRevisions() {
        val snapshots = jdbcClient.snapshots().joinToString(separator = "; ") {
            "${it.snapshotId} revision ${it.revision}: ${it.inputCount} inputs, checksum ${it.inputChecksum}"
        }
        val publication = jdbcClient.publications().single()
        log.info {
            "Late purchase: $snapshots; publication holds ${publication.snapshotId} (revision ${publication.revision})"
        }
    }

    /**
     * Puts a [MovableClock] in the context as its primary `Clock`. The scene's instants have no fraction, so the cut to
     * microseconds keeps them as they are.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class MovableClockConfig {
        @Primary
        @Bean
        fun movableClock(): MovableClock {
            return MovableClock(REVISION_1_AT)
        }
    }

    companion object {
        private val PERIOD_START: LocalDate = LocalDate.parse("2026-08-17")
        private const val REVISION_1_ID = "2026-08-17-r1"
        private const val REVISION_2_ID = "2026-08-17-r2"
        private val REVISION_1: JobParameters = jobParameters(snapshotId = REVISION_1_ID, revision = 1)
        private val REVISION_2: JobParameters = jobParameters(snapshotId = REVISION_2_ID, revision = 2)

        private val REVISION_1_AT: Instant = Instant.parse("2026-08-24T01:00:00Z")
        private val REVISION_2_AT: Instant = Instant.parse("2026-08-24T02:00:00Z")
        private val RERUN_AT: Instant = Instant.parse("2026-08-24T03:00:00Z")

        /**
         * The assignment's four inputs and the late purchase's line, `45\t8\t5\t2026-08-20T09:00:00.000000Z\n`, worked out
         * apart from the code with `printf … | shasum -a 256`.
         */
        private const val LATE_PURCHASE_CHECKSUM = "0c19c2a5661d9d3ce7f1e679c7311a74f220bf1f916b27b7fee00ad2ff156716"

        /** [instant] as the tables hold it: a UTC wall time. */
        private fun utcWallTime(instant: Instant): LocalDateTime {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
        }

        private val REVISION_1_SNAPSHOT = StoredSnapshot(
            snapshotId = REVISION_1_ID,
            periodStart = PERIOD_START,
            revision = 1,
            state = "FROZEN",
            frozenAt = utcWallTime(REVISION_1_AT),
            inputCount = 4,
            inputChecksum = ASSIGNMENT_CHECKSUM,
        )

        private val REVISION_1_SCORES = listOf(
            StoredScore(productId = 7, score = 5),
            StoredScore(productId = 8, score = 1),
            StoredScore(productId = 9, score = 5),
        )

        private val REVISION_1_ITEMS = listOf(
            StoredRankingItem(snapshotId = REVISION_1_ID, productId = 7, score = 5, productRank = 1),
            StoredRankingItem(snapshotId = REVISION_1_ID, productId = 9, score = 5, productRank = 2),
            StoredRankingItem(snapshotId = REVISION_1_ID, productId = 8, score = 1, productRank = 3),
        )

        private val REVISION_1_PUBLICATION = StoredPublication(
            periodStart = PERIOD_START,
            snapshotId = REVISION_1_ID,
            revision = 1,
            publishedAt = utcWallTime(REVISION_1_AT),
        )

        private val REVISION_2_PUBLICATION = StoredPublication(
            periodStart = PERIOD_START,
            snapshotId = REVISION_2_ID,
            revision = 2,
            publishedAt = utcWallTime(REVISION_2_AT),
        )
    }
}
