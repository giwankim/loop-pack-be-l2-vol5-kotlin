package com.loopers.job.restartprobe

import com.loopers.job.restartprobe.BatchRestartProbeJobConfig.Companion.PROBE_TABLE
import com.loopers.job.restartprobe.BatchRestartProbeJobConfig.Companion.READER_NAME
import com.loopers.job.restartprobe.BatchRestartProbeJobConfig.Companion.STEP_NAME
import com.loopers.job.restartprobe.BatchRestartProbeJobConfig.Companion.WRITTEN_TABLE
import com.loopers.job.restartprobe.BatchRestartProbeJobConfig.ProbeFailure
import com.loopers.support.test.BaseBatchJobTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import

/**
 * Shows on this repo's Spring Batch 6.0.6 that a failed chunk step restarts where it stopped (spec 237, seam 3). The first run
 * fails at product 9, after the chunk of 7 and 8 has committed. The restart uses the same parameters and the metadata the
 * first run left: nothing between the two runs empties a table.
 *
 * The probe's two tables are created here and belong to no production script. Each test empties them, and Batch's metadata,
 * before it runs, never between the two runs.
 */
@Import(BatchRestartProbeJobConfig::class)
class BatchRestartProbeTest @Autowired constructor(
    @param:Qualifier(BatchRestartProbeJobConfig.JOB_NAME) private val job: Job,
    private val probeFailure: ProbeFailure,
) : BaseBatchJobTest() {
    private val jobParameters: JobParameters = JobParametersBuilder()
        .addLong("minProductId", 7L)
        .toJobParameters()

    @BeforeEach
    fun setUp() {
        jdbcClient.sql("CREATE TABLE IF NOT EXISTS $PROBE_TABLE (product_id BIGINT NOT NULL PRIMARY KEY)").update()
        jdbcClient.sql(
            """
            CREATE TABLE IF NOT EXISTS $WRITTEN_TABLE (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                product_id BIGINT NOT NULL,
                job_execution_id BIGINT NOT NULL
            )
            """.trimIndent(),
        ).update()
        emptyTables(PROBE_TABLE, WRITTEN_TABLE)
        listOf(7L, 8L, 9L).forEach { productId ->
            jdbcClient.sql("INSERT INTO $PROBE_TABLE (product_id) VALUES (?)").param(productId).update()
        }
        jobOperatorTestUtils.job = job
    }

    @AfterEach
    fun tearDown() {
        probeFailure.failAtProductId = null
    }

    @Test
    fun `the first run fails at product 9 with 7 and 8 written and a stored read count of 2`() {
        // arrange
        probeFailure.failAtProductId = 9L

        // act
        val firstExecution = jobOperatorTestUtils.startJob(jobParameters)

        // assert
        assertThat(firstExecution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(writtenProductIds()).containsExactly(7L, 8L)
        assertThat(storedReadCount(firstExecution)).isEqualTo(2)
    }

    @Test
    fun `the restart is the same job instance in a new execution, and writes only product 9`() {
        // arrange
        probeFailure.failAtProductId = 9L
        val firstExecution = jobOperatorTestUtils.startJob(jobParameters)
        probeFailure.failAtProductId = null

        // act
        val restartExecution = jobOperatorTestUtils.startJob(jobParameters)

        // assert
        assertThat(restartExecution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(restartExecution.jobInstance.id).isEqualTo(firstExecution.jobInstance.id)
        assertThat(restartExecution.id).isNotEqualTo(firstExecution.id)
        assertThat(writtenRows())
            .containsExactly(
                WrittenRow(productId = 7L, jobExecutionId = firstExecution.id),
                WrittenRow(productId = 8L, jobExecutionId = firstExecution.id),
                WrittenRow(productId = 9L, jobExecutionId = restartExecution.id),
            )
    }

    private fun writtenProductIds(): List<Long> {
        return writtenRows().map { it.productId }
    }

    /** The probe's written rows in the order they were written. */
    private fun writtenRows(): List<WrittenRow> {
        return jdbcClient.sql("SELECT product_id, job_execution_id FROM $WRITTEN_TABLE ORDER BY id")
            .query { resultSet, _ -> WrittenRow(resultSet.getLong("product_id"), resultSet.getLong("job_execution_id")) }
            .list()
    }

    /** The reader's count in the step's `ExecutionContext` as the job repository stored it, read back from the database. */
    private fun storedReadCount(jobExecution: JobExecution): Int {
        val storedStepExecution = jobRepository.getLastStepExecution(jobExecution.jobInstance, STEP_NAME)

        return requireNotNull(storedStepExecution) { "No step execution of $STEP_NAME is stored" }
            .executionContext.getInt("$READER_NAME.read.count")
    }

    /** A product the probe wrote, and the job execution that wrote it. */
    private data class WrittenRow(val productId: Long, val jobExecutionId: Long)
}
