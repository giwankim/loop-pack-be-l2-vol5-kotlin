package com.loopers.job.restartprobe

import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * The restart probe of the assignment's section 3, rewritten for Spring Batch 6.0.6. It reads products from
 * [PROBE_TABLE] two per chunk and writes each processed one to [WRITTEN_TABLE] with the job execution that wrote it.
 * [ProbeFailure] makes the processor throw at one product, so a test can fail a run partway and restart it.
 *
 * A top-level `@TestConfiguration` is left out of component scanning: only the probe's test imports it, and no other
 * context has this job. The step runs on the application's transaction manager, so a chunk's writes and the step's
 * metadata commit together.
 */
@TestConfiguration(proxyBeanMethods = false)
class BatchRestartProbeJobConfig {
    @Bean
    fun probeFailure(): ProbeFailure {
        return ProbeFailure()
    }

    @Bean(JOB_NAME)
    fun batchRestartProbeJob(jobRepository: JobRepository, batchRestartProbeStep: Step): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .start(batchRestartProbeStep)
            .build()
    }

    @Bean(STEP_NAME)
    fun batchRestartProbeStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        purchaseProductProbeReader: JdbcCursorItemReader<Long>,
        probeFailure: ProbeFailure,
        purchaseProductProbeWriter: ItemWriter<Long>,
    ): Step {
        return StepBuilder(STEP_NAME, jobRepository)
            .chunk<Long, Long>(CHUNK_SIZE)
            .transactionManager(transactionManager)
            .reader(purchaseProductProbeReader)
            .processor(failingAt(probeFailure))
            .writer(purchaseProductProbeWriter)
            .build()
    }

    @StepScope
    @Bean
    fun purchaseProductProbeReader(
        dataSource: DataSource,
        @Value("#{jobParameters['minProductId']}") minProductId: Long,
    ): JdbcCursorItemReader<Long> {
        return JdbcCursorItemReaderBuilder<Long>()
            .name(READER_NAME)
            .dataSource(dataSource)
            .sql("SELECT product_id FROM $PROBE_TABLE WHERE product_id >= ? ORDER BY product_id")
            .queryArguments(minProductId)
            .rowMapper { resultSet, _ -> resultSet.getLong("product_id") }
            .build()
    }

    @StepScope
    @Bean
    fun purchaseProductProbeWriter(
        jdbcClient: JdbcClient,
        @Value("#{stepExecution.jobExecutionId}") jobExecutionId: Long,
    ): ItemWriter<Long> {
        return ItemWriter { chunk ->
            chunk.items.forEach { productId ->
                jdbcClient.sql("INSERT INTO $WRITTEN_TABLE (product_id, job_execution_id) VALUES (?, ?)")
                    .params(productId, jobExecutionId)
                    .update()
            }
        }
    }

    private fun failingAt(probeFailure: ProbeFailure): ItemProcessor<Long, Long> {
        return ItemProcessor { productId ->
            if (productId == probeFailure.failAtProductId) {
                throw IllegalStateException("The restart probe fails at product $productId")
            }

            productId
        }
    }

    /** The product the processor throws at, or `null` for a run that completes. A test sets it before a run. */
    class ProbeFailure {
        @Volatile
        var failAtProductId: Long? = null
    }

    companion object {
        const val JOB_NAME = "batchRestartProbe"
        const val STEP_NAME = "batchRestartProbeStep"
        const val READER_NAME = "purchaseProductProbe"
        const val PROBE_TABLE = "batch_restart_probe"
        const val WRITTEN_TABLE = "batch_restart_probe_written"
        private const val CHUNK_SIZE = 2
    }
}
