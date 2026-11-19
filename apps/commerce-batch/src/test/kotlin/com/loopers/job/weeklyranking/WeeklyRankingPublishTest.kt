package com.loopers.job.weeklyranking

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.support.WeeklyRankingJobs.RANKING_TABLES
import com.loopers.support.WeeklyRankingJobs.StoredPublication
import com.loopers.support.WeeklyRankingJobs.StoredRankingItem
import com.loopers.support.WeeklyRankingJobs.insertActivity
import com.loopers.support.WeeklyRankingJobs.insertAssignmentActivities
import com.loopers.support.WeeklyRankingJobs.jobParameters
import com.loopers.support.WeeklyRankingJobs.publications
import com.loopers.support.WeeklyRankingJobs.rankingItems
import com.loopers.support.test.BaseBatchJobTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
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
        insertPublication(otherPeriod)

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

    private fun insertPublication(publication: StoredPublication) {
        jdbcClient
            .sql(
                "INSERT INTO weekly_ranking_publication (period_start, snapshot_id, revision, published_at) VALUES (?, ?, ?, ?)",
            )
            .params(publication.periodStart, publication.snapshotId, publication.revision, publication.publishedAt)
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
