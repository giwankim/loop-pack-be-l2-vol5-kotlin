package com.loopers.job.weeklyranking

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingPublishStore
import com.loopers.support.WeeklyRankingJobs.RANKING_TABLES
import com.loopers.support.WeeklyRankingJobs.StoredPublication
import com.loopers.support.WeeklyRankingJobs.StoredRankingItem
import com.loopers.support.WeeklyRankingJobs.insertActivity
import com.loopers.support.WeeklyRankingJobs.insertAssignmentActivities
import com.loopers.support.WeeklyRankingJobs.insertPublication
import com.loopers.support.WeeklyRankingJobs.jobParameters
import com.loopers.support.WeeklyRankingJobs.publications
import com.loopers.support.WeeklyRankingJobs.rankingItems
import com.loopers.support.test.BaseBatchJobTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.jdbc.Sql
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Runs `weeklyRanking`, `freeze`, `score` then `publish`, and reads the weekly ranking and the publication it left (spec
 * 252, 게시). The context's clock is stopped at [CLOCK_INSTANT] by the nested [FixedClockConfig], so the publication's
 * published-at time is known.
 */
@TestPropertySource(properties = ["spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}"])
@Sql("/sql/purchase-activity.sql")
class WeeklyRankingPublishTest @Autowired constructor(
    @param:Qualifier(WeeklyRankingJobConfig.JOB_NAME) private val job: Job,
) : BaseBatchJobTest() {
    @MockkSpyBean
    private lateinit var publishStore: WeeklyRankingPublishStore

    @BeforeEach
    fun setUp() {
        emptyTables("purchase_activity", *RANKING_TABLES)
        jobOperatorTestUtils.job = job
    }

    /** Products 7 and 9 both score 5, so the smaller product ID comes first. */
    @Test
    fun `publishing the assignment's period completes with product 7, 9 and 8 ranked 1 to 3 by score, then product ID`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.rankingItems())
            .containsExactly(
                StoredRankingItem(snapshotId = "2026-08-17-r1", productId = 7, score = 5, productRank = 1),
                StoredRankingItem(snapshotId = "2026-08-17-r1", productId = 9, score = 5, productRank = 2),
                StoredRankingItem(snapshotId = "2026-08-17-r1", productId = 8, score = 1, productRank = 3),
            )
    }

    @Test
    fun `the period's publication holds the snapshot ID, its revision and the clock's time`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r3", revision = 3))

        // assert
        assertThat(jdbcClient.publications())
            .containsExactly(
                StoredPublication(
                    periodStart = LocalDate.parse("2026-08-17"),
                    snapshotId = "2026-08-17-r3",
                    revision = 3,
                    publishedAt = LocalDateTime.parse("2026-08-24T01:02:03.123456"),
                ),
            )
    }

    /** Publish adds no state: what a period shows is its publication row alone (spec 252). */
    @Test
    fun `after a published run the snapshot's state is still FROZEN`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.sql("SELECT state FROM weekly_ranking_snapshot").query(String::class.java).list())
            .containsExactly("FROZEN")
    }

    @Test
    fun `a period with no purchase activity completes with no ranking item and a publication of its snapshot`() {
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
        assertThat(jdbcClient.rankingItems()).isEmpty()
        assertThat(jdbcClient.publications())
            .extracting(StoredPublication::periodStart, StoredPublication::snapshotId)
            .containsExactly(tuple(LocalDate.parse("2026-08-17"), "2026-08-17-r1"))
    }

    @Test
    fun `publishing a period leaves another period's publication as it was`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val otherPeriod = StoredPublication(
            periodStart = LocalDate.parse("2026-08-10"),
            snapshotId = "2026-08-10-r1",
            revision = 1,
            publishedAt = LocalDateTime.parse("2026-08-17T00:05:00"),
        )
        jdbcClient.insertPublication(otherPeriod)

        // act
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jdbcClient.publications())
            .containsExactly(
                otherPeriod,
                StoredPublication(
                    periodStart = LocalDate.parse("2026-08-17"),
                    snapshotId = "2026-08-17-r1",
                    revision = 1,
                    publishedAt = LocalDateTime.parse("2026-08-24T01:02:03.123456"),
                ),
            )
    }

    /** Revision 1's ranking and publication are inserted by SQL, standing for a revision 1 run that completed first. */
    @Test
    fun `a later revision run replaces an earlier revision's publication and leaves the earlier ranking`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val earlierItems = listOf(
            StoredRankingItem(snapshotId = "2026-08-17-r1", productId = 7, score = 4, productRank = 1),
            StoredRankingItem(snapshotId = "2026-08-17-r1", productId = 8, score = 1, productRank = 2),
        )
        earlierItems.forEach(::insertRankingItem)
        jdbcClient.insertPublication(
            StoredPublication(
                periodStart = LocalDate.parse("2026-08-17"),
                snapshotId = "2026-08-17-r1",
                revision = 1,
                publishedAt = LocalDateTime.parse("2026-08-24T00:30:00"),
            ),
        )

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r2", revision = 2))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.publications())
            .containsExactly(
                StoredPublication(
                    periodStart = LocalDate.parse("2026-08-17"),
                    snapshotId = "2026-08-17-r2",
                    revision = 2,
                    publishedAt = LocalDateTime.parse("2026-08-24T01:02:03.123456"),
                ),
            )
        assertThat(jdbcClient.rankingItems().filter { it.snapshotId == "2026-08-17-r1" }).isEqualTo(earlierItems)
        assertThat(publishExitDescription(jobExecution)).isEmpty()
    }

    /** Revision 2's publication is inserted by SQL; no revision 2 run happens, so it stands for one that completed first. */
    @Test
    fun `an earlier revision run after a later one is published completes with its ranking and leaves the publication`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val laterPublication = StoredPublication(
            periodStart = LocalDate.parse("2026-08-17"),
            snapshotId = "2026-08-17-r2",
            revision = 2,
            publishedAt = LocalDateTime.parse("2026-08-24T00:30:00"),
        )
        jdbcClient.insertPublication(laterPublication)

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1", revision = 1))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.rankingItems())
            .extracting(StoredRankingItem::snapshotId, StoredRankingItem::productId, StoredRankingItem::productRank)
            .containsExactly(
                tuple("2026-08-17-r1", 7L, 1),
                tuple("2026-08-17-r1", 9L, 2),
                tuple("2026-08-17-r1", 8L, 3),
            )
        assertThat(jdbcClient.publications()).containsExactly(laterPublication)
        assertThat(publishExitDescription(jobExecution))
            .contains(
                "Publication of period 2026-08-17 not moved to snapshot 2026-08-17-r1 (period 2026-08-17, revision 1): " +
                    "it holds snapshot 2026-08-17-r2 (revision 2)",
            )
    }

    /** The check, the ranking and the publication are one transaction, so a refused snapshot leaves none of them. */
    @Test
    fun `a snapshot missing one product's score fails publish and leaves no ranking item and no publication`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        beforeTheFirstPublishCall("2026-08-17-r1") {
            jdbcClient
                .sql("DELETE FROM weekly_ranking_score WHERE snapshot_id = ? AND product_id = 9")
                .param("2026-08-17-r1")
                .update()
        }

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(publishExitDescription(jobExecution))
            .contains("snapshot 2026-08-17-r1 (period 2026-08-17, revision 1) has 2 scores for 3 products")
        assertThat(jdbcClient.rankingItems()).isEmpty()
        assertThat(jdbcClient.publications()).isEmpty()
    }

    /**
     * The inserted ranking holds as many items as the snapshot has scores, in an order the scores would not give, so a second
     * ranking would fail on the item's key or change the items.
     */
    @Test
    fun `a snapshot already ranked is not ranked again and is still published`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val rankedItems = listOf(
            StoredRankingItem(snapshotId = "2026-08-17-r1", productId = 8, score = 1, productRank = 1),
            StoredRankingItem(snapshotId = "2026-08-17-r1", productId = 9, score = 5, productRank = 2),
            StoredRankingItem(snapshotId = "2026-08-17-r1", productId = 7, score = 5, productRank = 3),
        )
        beforeTheFirstPublishCall("2026-08-17-r1") {
            rankedItems.forEach(::insertRankingItem)
        }

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.rankingItems()).isEqualTo(rankedItems)
        assertThat(jdbcClient.publications())
            .extracting(StoredPublication::periodStart, StoredPublication::snapshotId)
            .containsExactly(tuple(LocalDate.parse("2026-08-17"), "2026-08-17-r1"))
    }

    /** Runs [action] inside [snapshotId]'s publish transaction, before the publish store's first call, then lets it go on. */
    private fun beforeTheFirstPublishCall(snapshotId: String, action: () -> Unit) {
        every { publishStore.countScores(snapshotId) } answers {
            action()
            callOriginal()
        }
    }

    /** Not a `StepExecution`: `@SpringBatchTest`'s step-scope listener would take such a method for a step context factory. */
    private fun publishExitDescription(jobExecution: JobExecution): String {
        return jobExecution.stepExecutions
            .single { it.stepName == WeeklyRankingJobConfig.PUBLISH_STEP_NAME }
            .exitStatus
            .exitDescription
    }

    private fun insertRankingItem(item: StoredRankingItem) {
        jdbcClient
            .sql("INSERT INTO weekly_ranking_item (snapshot_id, product_id, score, product_rank) VALUES (?, ?, ?, ?)")
            .params(item.snapshotId, item.productId, item.score, item.productRank)
            .update()
    }

    /** Stops the context's primary `Clock`. Its instant has nanoseconds, so the published-at time shows the cut to microseconds. */
    @TestConfiguration(proxyBeanMethods = false)
    class FixedClockConfig {
        @Primary
        @Bean
        fun fixedClock(): Clock {
            return Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC)
        }
    }

    companion object {
        val CLOCK_INSTANT: Instant = Instant.parse("2026-08-24T01:02:03.123456789Z")
    }
}
