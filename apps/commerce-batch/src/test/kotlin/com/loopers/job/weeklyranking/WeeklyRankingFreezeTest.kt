package com.loopers.job.weeklyranking

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingSnapshotStore
import com.loopers.support.WeeklyRankingInputs.ASSIGNMENT_CHECKSUM
import com.loopers.support.WeeklyRankingInputs.ASSIGNMENT_INPUTS
import com.loopers.support.WeeklyRankingInputs.EMPTY_CHECKSUM
import com.loopers.support.test.BaseBatchJobTest
import com.ninjasquad.springmockk.MockkSpyBean
import com.zaxxer.hikari.HikariDataSource
import io.mockk.clearMocks
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.jdbc.Sql
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * Runs `weeklyRanking`, whose one step is `freeze`, and reads what it left (spec 237, seam 2). Purchase activities go in by
 * SQL into the test-only `purchase_activity`. Times cross JDBC as UTC `LocalDateTime` on both sides, while the JVM runs in
 * Asia/Seoul, so a nine-hour shift anywhere would show.
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
        emptyTables("purchase_activity", "weekly_ranking_snapshot_input", "weekly_ranking_snapshot")
        jobOperatorTestUtils.job = job
    }

    @Test
    fun `freezing the assignment's week completes with one snapshot input per order and product`() {
        // arrange
        insertAssignmentActivities()

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(snapshotInputs("2026-08-17-r1"))
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
        insertAssignmentActivities()

        // act
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1", revision = 3))

        // assert
        assertThat(snapshots())
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

    @Test
    fun `a period with no purchase activity is frozen with count 0 and the empty-input checksum`() {
        // arrange
        insertActivity(orderId = 50, productId = 7, quantity = 1, confirmedAt = utc("2026-08-12T10:00:00"))

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(snapshotInputs("2026-08-17-r1")).isEmpty()
        assertThat(snapshots().single())
            .extracting(StoredSnapshot::inputCount, StoredSnapshot::inputChecksum)
            .containsExactly(0L, EMPTY_CHECKSUM)
    }

    @Test
    fun `a freeze that fails after writing its inputs ends FAILED and leaves no row in either ranking table`() {
        // arrange
        insertAssignmentActivities()
        failAfterTheInputsAreWritten("2026-08-17-r1")

        // act
        val jobExecution = jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // assert
        assertThat(jobExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(snapshotInputs("2026-08-17-r1")).isEmpty()
        assertThat(snapshots()).isEmpty()
    }

    @Test
    fun `rerunning a failed freeze with the same parameters completes the same job instance with the full snapshot`() {
        // arrange
        insertAssignmentActivities()
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
        assertThat(snapshotInputs("2026-08-17-r1")).hasSize(4)
        assertThat(snapshots().single())
            .extracting(StoredSnapshot::inputCount, StoredSnapshot::inputChecksum)
            .containsExactly(4L, ASSIGNMENT_CHECKSUM)
    }

    @Test
    fun `a purchase activity recorded from another connection during the freeze is not blocked and is not in the snapshot`() {
        // arrange
        insertAssignmentActivities()
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
        assertThat(snapshotInputs("2026-08-17-r1").map(StoredSnapshotInput::orderId)).doesNotContain(45L)
        assertThat(snapshots().single())
            .extracting(StoredSnapshot::inputCount, StoredSnapshot::inputChecksum)
            .containsExactly(4L, ASSIGNMENT_CHECKSUM)
    }

    @Test
    fun `running the schema script again on startup neither fails nor removes existing rows`() {
        // arrange
        insertAssignmentActivities()
        jobOperatorTestUtils.startJob(jobParameters(snapshotId = "2026-08-17-r1"))

        // act
        val ranScripts = sqlInitializer.initializeDatabase()

        // assert
        assertThat(ranScripts).isTrue()
        assertThat(snapshots()).hasSize(1)
        assertThat(snapshotInputs("2026-08-17-r1")).hasSize(4)
    }

    private fun jobParameters(
        periodStart: LocalDate = LocalDate.parse("2026-08-17"),
        snapshotId: String,
        revision: Long = 1,
    ): JobParameters {
        return JobParametersBuilder()
            .addLocalDate("periodStart", periodStart)
            .addString("snapshotId", snapshotId)
            .addLong("revision", revision)
            .toJobParameters()
    }

    /** The assignment's section 6 orders, inserted in reverse key order so the freeze's own order shows. */
    private fun insertAssignmentActivities() {
        ASSIGNMENT_INPUTS.reversed().forEach { input ->
            insertActivity(
                orderId = input.orderId,
                productId = input.productId,
                quantity = input.quantity,
                confirmedAt = LocalDateTime.ofInstant(input.confirmedAt, ZoneOffset.UTC),
            )
        }
    }

    /** Inserts a purchase activity confirmed at [confirmedAt], a UTC wall time. */
    private fun insertActivity(orderId: Long, productId: Long, quantity: Int, confirmedAt: LocalDateTime) {
        jdbcClient.sql(INSERT_ACTIVITY_SQL)
            .params(orderId, productId, quantity, confirmedAt)
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

    /** A UTC wall time, as the tables hold it. */
    private fun utc(text: String): LocalDateTime {
        return LocalDateTime.parse(text)
    }

    private fun snapshotInputs(snapshotId: String): List<StoredSnapshotInput> {
        return jdbcClient
            .sql(
                """
                SELECT order_id, product_id, quantity, confirmed_at FROM weekly_ranking_snapshot_input
                WHERE snapshot_id = ? ORDER BY order_id, product_id
                """.trimIndent(),
            )
            .param(snapshotId)
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

    private fun snapshots(): List<StoredSnapshot> {
        return jdbcClient.sql("SELECT * FROM weekly_ranking_snapshot ORDER BY snapshot_id")
            .query { resultSet, _ ->
                StoredSnapshot(
                    snapshotId = resultSet.getString("snapshot_id"),
                    periodStart = resultSet.getObject("period_start", LocalDate::class.java),
                    revision = resultSet.getLong("revision"),
                    state = resultSet.getString("state"),
                    frozenAt = resultSet.getObject("frozen_at", LocalDateTime::class.java),
                    inputCount = resultSet.getLong("input_count"),
                    inputChecksum = resultSet.getString("input_checksum"),
                )
            }
            .list()
    }

    private data class StoredSnapshotInput(
        val orderId: Long,
        val productId: Long,
        val quantity: Int,
        val confirmedAt: LocalDateTime,
    )

    private data class StoredSnapshot(
        val snapshotId: String,
        val periodStart: LocalDate,
        val revision: Long,
        val state: String,
        val frozenAt: LocalDateTime,
        val inputCount: Long,
        val inputChecksum: String,
    )

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

        private const val INSERT_ACTIVITY_SQL =
            "INSERT INTO purchase_activity (order_id, product_id, quantity, confirmed_at) VALUES (?, ?, ?, ?)"
    }
}
