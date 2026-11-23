package com.loopers.job.weeklyranking

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingSnapshotStore
import com.loopers.support.WeeklyRankingInputs.ASSIGNMENT_CHECKSUM
import com.loopers.support.WeeklyRankingInputs.EMPTY_CHECKSUM
import com.loopers.support.WeeklyRankingJobs.INSERT_ACTIVITY_SQL
import com.loopers.support.WeeklyRankingJobs.RANKING_TABLES
import com.loopers.support.WeeklyRankingJobs.StoredSnapshot
import com.loopers.support.WeeklyRankingJobs.StoredSnapshotInput
import com.loopers.support.WeeklyRankingJobs.insertActivity
import com.loopers.support.WeeklyRankingJobs.insertAssignmentActivities
import com.loopers.support.WeeklyRankingJobs.jobParameters
import com.loopers.support.WeeklyRankingJobs.snapshotInputs
import com.loopers.support.WeeklyRankingJobs.snapshots
import com.loopers.support.test.BaseBatchJobTest
import com.ninjasquad.springmockk.MockkSpyBean
import com.zaxxer.hikari.HikariDataSource
import io.mockk.clearMocks
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.InvalidJobParametersException
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.dao.DuplicateKeyException
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.jdbc.Sql
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import javax.sql.DataSource

/**
 * Runs `weeklyRanking` and reads what its `freeze` step left (spec 237, seam 2). The scores `score` leaves after it are
 * `WeeklyRankingScoreTest`'s, and a failed freeze runs no `score` step, which the scene of a freeze failing after writing its
 * inputs shows. Purchase activities go in by SQL into the test-only `purchase_activity`. Times cross JDBC as UTC
 * `LocalDateTime` on both sides, while the JVM runs in Asia/Seoul, so a nine-hour shift anywhere would show.
 *
 * The context's clock is stopped at [FROZEN_CLOCK_INSTANT] by the nested [FrozenClockConfig].
 *
 * [snapshotStore] is a spy, so a scene can act at one point inside the freeze transaction: when the inputs' count and
 * checksum are computed, after the inputs are written and before the snapshot row is.
 */
@TestPropertySource(properties = ["spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}"])
@Sql("/sql/purchase-activity.sql")
class WeeklyRankingFreezeTest @Autowired constructor(
    @param:Qualifier(WeeklyRankingJobConfig.JOB_NAME) private val job: Job,
    private val sqlInitializer: ApplicationDataSourceScriptDatabaseInitializer,
    private val dataSource: DataSource,
) : BaseBatchJobTest() {
    @MockkSpyBean
    private lateinit var snapshotStore: WeeklyRankingSnapshotStore

    @BeforeEach
    fun setUp() {
        emptyTables("purchase_activity", *RANKING_TABLES)
        jobOperatorTestUtils.job = job
    }

    @Test
    fun `freezing the assignment's week completes with one snapshot input per order and product`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1"))
            .containsExactly(
                StoredSnapshotInput(orderId = 42, productId = 7, quantity = 2, confirmedAt = utc("2026-08-17T00:00:00")),
                StoredSnapshotInput(orderId = 42, productId = 8, quantity = 1, confirmedAt = utc("2026-08-17T00:00:00")),
                StoredSnapshotInput(orderId = 43, productId = 7, quantity = 3, confirmedAt = utc("2026-08-19T13:45:30.123456")),
                StoredSnapshotInput(orderId = 44, productId = 9, quantity = 5, confirmedAt = utc("2026-08-23T23:59:59.999999")),
            )
    }

    @Test
    fun `the snapshot is frozen with the inputs' count and checksum, the given period and revision, and the clock's time`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1", revision = 3))

        // assert
        assertThat(jdbcClient.snapshots())
            .containsExactly(
                StoredSnapshot(
                    snapshotId = "2026-08-17-r1",
                    periodStart = LocalDate.parse("2026-08-17"),
                    revision = 3,
                    state = "FROZEN",
                    frozenAt = utc("2026-08-24T01:02:03.123456"),
                    inputCount = 4,
                    inputChecksum = ASSIGNMENT_CHECKSUM,
                ),
            )
    }

    /**
     * A ranking period is a UTC week, start included and end excluded. Bound or read through the JVM's zone, Asia/Seoul, the
     * period would move nine hours later: the Monday 00:00 activity would fall out and the next Monday's would come in.
     */
    @Test
    fun `an activity at Monday midnight UTC is in the period, and ones a microsecond earlier or at the next Monday are not`() {
        // arrange: the scene's premise, asserted rather than assumed so a UTC-zone run fails instead of skipping
        assertThat(TimeZone.getDefault().id).isEqualTo("Asia/Seoul")
        jdbcClient.insertActivity(orderId = 60, productId = 7, quantity = 1, confirmedAt = utc("2026-08-16T23:59:59.999999"))
        jdbcClient.insertActivity(orderId = 61, productId = 7, quantity = 2, confirmedAt = utc("2026-08-17T00:00:00"))
        jdbcClient.insertActivity(orderId = 62, productId = 7, quantity = 3, confirmedAt = utc("2026-08-24T00:00:00"))

        // act
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1"))
            .containsExactly(
                StoredSnapshotInput(orderId = 61, productId = 7, quantity = 2, confirmedAt = utc("2026-08-17T00:00:00")),
            )
        assertThat(jdbcClient.snapshots().single())
            .extracting(StoredSnapshot::inputCount, StoredSnapshot::inputChecksum)
            .containsExactly(1L, BOUNDARY_CHECKSUM)
    }

    /** The deleted activity sits inside the week, so only `deleted_at` keeps it out. */
    @Test
    fun `a soft-deleted activity in the period is in neither the snapshot inputs nor their count and checksum`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        jdbcClient.insertActivity(orderId = 45, productId = 7, quantity = 4, confirmedAt = utc("2026-08-20T12:00:00"))
        softDeleteActivity(orderId = 45, productId = 7, deletedAt = utc("2026-08-21T12:00:00"))

        // act
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1").map { it.orderId }).containsExactly(42, 42, 43, 44)
        assertThat(jdbcClient.snapshots().single())
            .extracting(StoredSnapshot::inputCount, StoredSnapshot::inputChecksum)
            .containsExactly(4L, ASSIGNMENT_CHECKSUM)
    }

    @Test
    fun `a period with no purchase activity is frozen with count 0 and the empty-input checksum`() {
        // arrange
        jdbcClient.insertActivity(orderId = 50, productId = 7, quantity = 1, confirmedAt = utc("2026-08-12T10:00:00"))

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1")).isEmpty()
        assertThat(jdbcClient.snapshots().single())
            .extracting(StoredSnapshot::inputCount, StoredSnapshot::inputChecksum)
            .containsExactly(0L, EMPTY_CHECKSUM)
    }

    @Test
    fun `a freeze that fails after writing its inputs ends FAILED and leaves no row in either ranking table`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failAfterTheInputsAreWritten("2026-08-17-r1")

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(jobExecution.stepExecutions.map { it.stepName }).containsExactly(WeeklyRankingJobConfig.FREEZE_STEP_NAME)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1")).isEmpty()
        assertThat(jdbcClient.snapshots()).isEmpty()
    }

    @Test
    fun `rerunning a failed freeze with the same parameters completes the same job instance with the full snapshot`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        failAfterTheInputsAreWritten("2026-08-17-r1")
        val failedExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))
        assertThat(failedExecution.status).isEqualTo(BatchStatus.FAILED)
        clearMocks(snapshotStore)

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jobExecution.jobInstance.id).isEqualTo(failedExecution.jobInstance.id)
        assertThat(jobExecution.id).isNotEqualTo(failedExecution.id)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1")).hasSize(4)
        assertThat(jdbcClient.snapshots().single())
            .extracting(StoredSnapshot::inputCount, StoredSnapshot::inputChecksum)
            .containsExactly(4L, ASSIGNMENT_CHECKSUM)
    }

    @Test
    fun `a purchase activity recorded from another connection during the freeze is not blocked and is not in the snapshot`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val lateInserts = mutableListOf<Result<Unit>>()
        afterTheInputsAreWritten("2026-08-17-r1") {
            lateInserts += runCatching {
                insertActivityFromAnotherConnection(
                    orderId = 45,
                    productId = 7,
                    quantity = 4,
                    confirmedAt = utc("2026-08-20T12:00:00"),
                )
            }
        }

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(lateInserts.single().exceptionOrNull()).isNull()
        assertThat(activityOrderIds()).contains(45L)
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1").map(StoredSnapshotInput::orderId)).doesNotContain(45L)
        assertThat(jdbcClient.snapshots().single())
            .extracting(StoredSnapshot::inputCount, StoredSnapshot::inputChecksum)
            .containsExactly(4L, ASSIGNMENT_CHECKSUM)
    }

    @Test
    fun `a freeze under an existing snapshot ID for another period fails and leaves the existing snapshot as it was`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        jdbcClient.insertActivity(orderId = 60, productId = 7, quantity = 4, confirmedAt = utc("2026-08-25T10:00:00"))
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))
        val snapshotsBefore = jdbcClient.snapshots()
        val inputsBefore = jdbcClient.snapshotInputs("2026-08-17-r1")

        // act
        val jobExecution = jobOperatorTestUtils.startJob(
            jobParameters(periodStart = LocalDate.parse("2026-08-24"), snapshotId = "2026-08-17-r1"),
        )

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(jobExecution.stepExecutions.single().exitStatus.exitDescription)
            .contains(
                "Freeze of snapshot 2026-08-17-r1 (period 2026-08-24, revision 1) " +
                    "conflicts with snapshot 2026-08-17-r1 (period 2026-08-17, revision 1)",
            )
        assertThat(jdbcClient.snapshots()).isEqualTo(snapshotsBefore)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1")).isEqualTo(inputsBefore)
    }

    @Test
    fun `a freeze under an existing snapshot ID for another revision of the same period fails and writes nothing`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))
        val snapshotsBefore = jdbcClient.snapshots()
        val inputsBefore = jdbcClient.snapshotInputs("2026-08-17-r1")

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1", revision = 2))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(jobExecution.stepExecutions.single().exitStatus.exitDescription)
            .contains(
                "Freeze of snapshot 2026-08-17-r1 (period 2026-08-17, revision 2) " +
                    "conflicts with snapshot 2026-08-17-r1 (period 2026-08-17, revision 1)",
            )
        assertThat(jdbcClient.snapshots()).isEqualTo(snapshotsBefore)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1")).isEqualTo(inputsBefore)
    }

    @Test
    fun `a freeze of an already frozen period and revision under a new snapshot ID fails and writes nothing`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))
        val snapshotsBefore = jdbcClient.snapshots()
        val inputsBefore = jdbcClient.snapshotInputs("2026-08-17-r1")

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1-again"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(jobExecution.stepExecutions.single().exitStatus.exitDescription)
            .contains(
                "Freeze of snapshot 2026-08-17-r1-again (period 2026-08-17, revision 1) " +
                    "conflicts with snapshot 2026-08-17-r1 (period 2026-08-17, revision 1)",
            )
        assertThat(jdbcClient.snapshots()).isEqualTo(snapshotsBefore)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1")).isEqualTo(inputsBefore)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1-again")).isEmpty()
    }

    @Test
    fun `a new revision of a frozen period freezes normally under a new snapshot ID`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r2", revision = 2))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.snapshots())
            .extracting(StoredSnapshot::snapshotId, StoredSnapshot::revision, StoredSnapshot::inputChecksum)
            .containsExactly(
                tuple("2026-08-17-r1", 1L, ASSIGNMENT_CHECKSUM),
                tuple("2026-08-17-r2", 2L, ASSIGNMENT_CHECKSUM),
            )
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r2")).hasSize(4)
    }

    @Test
    fun `the database refuses a second snapshot row for the same period and revision`() {
        // arrange
        insertSnapshotRow(snapshotId = "2026-08-17-r1")

        // act
        val exception = assertThrows<DuplicateKeyException> { insertSnapshotRow(snapshotId = "2026-08-17-r1-again") }

        // assert
        assertThat(exception).hasMessageContaining("UK_WEEKLY_RANKING_SNAPSHOT_ON_PERIOD_START_AND_REVISION")
    }

    @Test
    fun `the database refuses a second snapshot row under the same snapshot ID`() {
        // arrange
        insertSnapshotRow(snapshotId = "2026-08-17-r1")

        // act
        val exception = assertThrows<DuplicateKeyException> { insertSnapshotRow(snapshotId = "2026-08-17-r1", revision = 2) }

        // assert
        assertThat(exception).hasMessageContaining("weekly_ranking_snapshot.PRIMARY")
    }

    @Test
    fun `running the schema script again on startup neither fails nor removes existing rows`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // act
        val ranScripts = sqlInitializer.initializeDatabase()

        // assert
        assertThat(ranScripts).isTrue()
        assertThat(jdbcClient.snapshots()).hasSize(1)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1")).hasSize(4)
    }

    @Test
    fun `a periodStart that is not a Monday is refused before anything is frozen`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val tuesday = jobParameters(periodStart = LocalDate.parse("2026-08-18"), snapshotId = "2026-08-18-r1")

        // act
        val exception = assertThrows<InvalidJobParametersException> { jobOperatorTestUtils.startJob(tuesday) }

        // assert
        assertThat(exception).hasMessage("periodStart must be a Monday, but 2026-08-18 is a TUESDAY")
        assertNothingExecutedOrFrozen()
    }

    @Test
    fun `a blank snapshotId is refused before anything is frozen`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        val exception = assertThrows<InvalidJobParametersException> {
            jobOperatorTestUtils.startJob(jobParameters(snapshotId = "  "))
        }

        // assert
        assertThat(exception).hasMessage("snapshotId must not be blank")
        assertNothingExecutedOrFrozen()
    }

    @Test
    fun `a snapshotId longer than the column is refused before anything is frozen`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val tooLong = "r".repeat(101)

        // act
        val exception = assertThrows<InvalidJobParametersException> {
            jobOperatorTestUtils.startJob(jobParameters(snapshotId = tooLong))
        }

        // assert
        assertThat(exception).hasMessage("snapshotId must be at most 100 characters, but has 101")
        assertNothingExecutedOrFrozen()
    }

    @Test
    fun `a revision below 1 is refused before anything is frozen`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        val exception = assertThrows<InvalidJobParametersException> {
            jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r0", revision = 0))
        }

        // assert
        assertThat(exception).hasMessage("revision must be at least 1, but was 0")
        assertNothingExecutedOrFrozen()
    }

    @Test
    fun `a launch without periodStart is refused before anything is frozen`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val withoutPeriodStart = JobParametersBuilder()
            .addString("snapshotId", "2026-08-17-r1")
            .addLong("revision", 1)
            .toJobParameters()

        // act
        val exception = assertThrows<InvalidJobParametersException> { jobOperatorTestUtils.startJob(withoutPeriodStart) }

        // assert
        assertThat(exception).hasMessageContaining("periodStart is missing")
        assertNothingExecutedOrFrozen()
    }

    @Test
    fun `a launch without snapshotId is refused before anything is frozen`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val withoutSnapshotId = JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.parse("2026-08-17"))
            .addLong("revision", 1)
            .toJobParameters()

        // act
        val exception = assertThrows<InvalidJobParametersException> { jobOperatorTestUtils.startJob(withoutSnapshotId) }

        // assert
        assertThat(exception).hasMessageContaining("snapshotId is missing")
        assertNothingExecutedOrFrozen()
    }

    @Test
    fun `a launch without revision is refused before anything is frozen`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val withoutRevision = JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.parse("2026-08-17"))
            .addString("snapshotId", "2026-08-17-r1")
            .toJobParameters()

        // act
        val exception = assertThrows<InvalidJobParametersException> { jobOperatorTestUtils.startJob(withoutRevision) }

        // assert
        assertThat(exception).hasMessageContaining("revision is missing")
        assertNothingExecutedOrFrozen()
    }

    /** What a command line gives when the operator leaves the type off: `periodStart=2026-08-17` is a `String`. */
    @Test
    fun `a periodStart given as a String is refused with the spelling that makes it a LocalDate`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        val stringPeriodStart = JobParametersBuilder()
            .addString("periodStart", "2026-08-17")
            .addString("snapshotId", "2026-08-17-r1")
            .addLong("revision", 1)
            .toJobParameters()

        // act
        val exception = assertThrows<InvalidJobParametersException> { jobOperatorTestUtils.startJob(stringPeriodStart) }

        // assert
        assertThat(exception).hasMessageContaining("periodStart=2026-08-17,java.time.LocalDate")
        assertNothingExecutedOrFrozen()
    }

    /** The context's clock stands at 2026-08-24, a week before this period starts. No check reads it. */
    @Test
    fun `a periodStart in the future is accepted and freezes an empty snapshot`() {
        // arrange
        jdbcClient.insertAssignmentActivities()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(
            jobParameters(periodStart = LocalDate.parse("2026-08-31"), snapshotId = "2026-08-31-r1"),
        )

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(jdbcClient.snapshots().single())
            .extracting(StoredSnapshot::periodStart, StoredSnapshot::inputCount)
            .containsExactly(LocalDate.parse("2026-08-31"), 0L)
    }

    @Test
    fun `launching a completed run again with the same three values is refused and leaves the snapshot as it was`() {
        // arrange
        jdbcClient.insertAssignmentActivities()
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))
        val snapshotsBefore = jdbcClient.snapshots()
        val inputsBefore = jdbcClient.snapshotInputs("2026-08-17-r1")

        // act
        assertThrows<JobInstanceAlreadyCompleteException> {
            jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))
        }

        // assert
        assertThat(jdbcClient.snapshots()).isEqualTo(snapshotsBefore)
        assertThat(jdbcClient.snapshotInputs("2026-08-17-r1")).isEqualTo(inputsBefore)
        assertThat(jobExecutionCount()).isEqualTo(1)
    }

    /**
     * A refused launch starts no job execution and leaves no row in either ranking table. The job instance is left out on
     * purpose: Batch 6.0.6 creates it before it validates, and Batch 6.1 does not (spec 237, 확인한 사실 6).
     */
    private fun assertNothingExecutedOrFrozen() {
        assertThat(jobExecutionCount()).isZero()
        assertThat(jdbcClient.snapshots()).isEmpty()
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM weekly_ranking_snapshot_input").query(Long::class.java).single())
            .isZero()
    }

    private fun jobExecutionCount(): Long {
        return jdbcClient.sql("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION").query(Long::class.java).single()
    }

    /** Soft-deletes the activity of [orderId] and [productId] at [deletedAt], a UTC wall time, as the streamer would. */
    private fun softDeleteActivity(orderId: Long, productId: Long, deletedAt: LocalDateTime) {
        jdbcClient.sql("UPDATE purchase_activity SET deleted_at = ? WHERE order_id = ? AND product_id = ?")
            .params(deletedAt, orderId, productId)
            .update()
    }

    /**
     * Inserts a purchase activity as the streamer would, on a connection of its own outside the pool and the freeze's
     * transaction, committed at once. It waits at most one second for a row lock, so a freeze that locked the period's range
     * would make it fail with MySQL 1205 instead of hanging the scene. A pool connection is not used: its session keeps the
     * pool's three-second wait from `jpa.yml`, and a changed value would outlive the scene.
     */
    private fun insertActivityFromAnotherConnection(
        orderId: Long,
        productId: Long,
        quantity: Int,
        confirmedAt: LocalDateTime,
    ) {
        val pool = dataSource.unwrap(HikariDataSource::class.java)
        DriverManager.getConnection(pool.jdbcUrl, pool.username, pool.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET SESSION innodb_lock_wait_timeout = 1")
            }
            connection.prepareStatement(INSERT_ACTIVITY_SQL).use { statement ->
                statement.setLong(1, orderId)
                statement.setLong(2, productId)
                statement.setInt(3, quantity)
                statement.setObject(4, confirmedAt)
                statement.executeUpdate()
            }
        }
    }

    /**
     * [snapshotId]'s freeze fails after its inputs are written and before its snapshot row is, inside the freeze
     * transaction.
     */
    private fun failAfterTheInputsAreWritten(snapshotId: String) {
        every { snapshotStore.digestInputs(snapshotId) } throws IllegalStateException("fails after writing the inputs")
    }

    /**
     * Runs [action] in [snapshotId]'s freeze after its inputs are written and before its count and checksum are computed,
     * inside the freeze transaction, then lets the freeze go on.
     */
    private fun afterTheInputsAreWritten(snapshotId: String, action: () -> Unit) {
        every { snapshotStore.digestInputs(snapshotId) } answers {
            action()
            callOriginal()
        }
    }

    private fun activityOrderIds(): List<Long> {
        return jdbcClient.sql("SELECT order_id FROM purchase_activity")
            .query { resultSet, _ ->
                resultSet.getLong("order_id")
            }
            .list()
    }

    /** Inserts a frozen snapshot row by SQL, past the freeze, for period 2026-08-17. */
    private fun insertSnapshotRow(snapshotId: String, revision: Long = 1) {
        jdbcClient
            .sql(
                """
                INSERT INTO weekly_ranking_snapshot
                    (snapshot_id, period_start, revision, state, frozen_at, input_count, input_checksum)
                VALUES (?, ?, ?, 'FROZEN', ?, 0, ?)
                """.trimIndent(),
            )
            .params(snapshotId, LocalDate.parse("2026-08-17"), revision, utc("2026-08-24T00:00:00"), EMPTY_CHECKSUM)
            .update()
    }

    /** A UTC wall time, as the tables hold it. */
    private fun utc(text: String): LocalDateTime {
        return LocalDateTime.parse(text)
    }

    /** Stops the context's primary `Clock`. Its instant has nanoseconds, so the frozen-at scene shows the cut to microseconds. */
    @TestConfiguration(proxyBeanMethods = false)
    class FrozenClockConfig {
        @Primary
        @Bean
        fun frozenClock(): Clock {
            return Clock.fixed(FROZEN_CLOCK_INSTANT, ZoneOffset.UTC)
        }
    }

    companion object {
        val FROZEN_CLOCK_INSTANT: Instant = Instant.parse("2026-08-24T01:02:03.123456789Z")

        /** `printf '61\t7\t2\t2026-08-17T00:00:00.000000Z\n' | shasum -a 256`: the boundary scene's one input. */
        const val BOUNDARY_CHECKSUM = "e02703d4c639b8e8eb6fff68f502505ae1bbbdf5f11cdca9f1a49a4949cd754e"
    }
}
