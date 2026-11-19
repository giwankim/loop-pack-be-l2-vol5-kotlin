package com.loopers.batch.job.weeklyranking

import com.loopers.batch.job.weeklyranking.step.FreezeTasklet
import com.loopers.batch.job.weeklyranking.step.ProductScore
import com.loopers.batch.job.weeklyranking.step.PublishTasklet
import com.loopers.batch.job.weeklyranking.step.ScoreProcessor
import com.loopers.batch.job.weeklyranking.step.ScoreProductReader
import com.loopers.batch.job.weeklyranking.step.ScoreWriter
import com.loopers.batch.listener.JobListener
import com.loopers.batch.listener.StepMonitorListener
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.DefaultTransactionAttribute

/**
 * The weekly ranking job (`CONTEXT.md` 주간 랭킹). It is identified by three parameters an operator gives on the command line,
 * `periodStart` (`LocalDate`), `snapshotId` (`String`) and `revision` (`Long`), with no incrementer, run ID or clock value, so
 * a rerun with the same three is the same job instance. It runs `freeze`, `score`, then `publish`. A rerun of a failed run
 * skips the steps that completed, so the snapshot stays as the first run froze it.
 *
 * Every step runs on the application's transaction manager, so a step's writes and its metadata commit together. The
 * freeze's transaction is READ COMMITTED: the copy's `INSERT … SELECT` then takes no locks on `purchase_activity`, and the
 * streamer can record a purchase while a freeze runs (spec 237, 확인한 사실 1).
 *
 * `score` is a chunk step with no isolation override, no skip and no retry: it reads and writes only the frozen inputs and
 * its own score table, and any failure fails the run. A chunk's scores and the reader's position commit together, so a
 * rerun continues after the last committed product (spec 246).
 *
 * `publish` is one tasklet in one transaction, with no isolation override: it checks the scores cover the snapshot's
 * products, ranks them and writes the period's publication, so no reader sees a ranking without its publication or the
 * other way round (spec 252).
 */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration(proxyBeanMethods = false)
class WeeklyRankingJobConfig {
    @Bean(JOB_NAME)
    fun weeklyRankingJob(
        jobRepository: JobRepository,
        jobListener: JobListener,
        @Qualifier(FREEZE_STEP_NAME) freezeStep: Step,
        @Qualifier(SCORE_STEP_NAME) scoreStep: Step,
        @Qualifier(PUBLISH_STEP_NAME) publishStep: Step,
    ): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .validator(WeeklyRankingJobParametersValidator())
            .start(freezeStep)
            .next(scoreStep)
            .next(publishStep)
            .listener(jobListener)
            .build()
    }

    @Bean(FREEZE_STEP_NAME)
    fun freezeStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        freezeTasklet: FreezeTasklet,
        stepMonitorListener: StepMonitorListener,
    ): Step {
        return StepBuilder(FREEZE_STEP_NAME, jobRepository)
            .tasklet(freezeTasklet, transactionManager)
            .transactionAttribute(readCommitted())
            .listener(stepMonitorListener)
            .build()
    }

    @Bean(SCORE_STEP_NAME)
    fun scoreStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        properties: WeeklyRankingScoreProperties,
        scoreProductReader: ScoreProductReader,
        scoreProcessor: ScoreProcessor,
        scoreWriter: ScoreWriter,
        stepMonitorListener: StepMonitorListener,
    ): Step {
        return StepBuilder(SCORE_STEP_NAME, jobRepository)
            .chunk<Long, ProductScore>(properties.chunkSize)
            .transactionManager(transactionManager)
            .reader(scoreProductReader)
            .processor(scoreProcessor)
            .writer(scoreWriter)
            .listener(stepMonitorListener)
            .build()
    }

    @Bean(PUBLISH_STEP_NAME)
    fun publishStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        publishTasklet: PublishTasklet,
        stepMonitorListener: StepMonitorListener,
    ): Step {
        return StepBuilder(PUBLISH_STEP_NAME, jobRepository)
            .tasklet(publishTasklet, transactionManager)
            .listener(stepMonitorListener)
            .build()
    }

    private fun readCommitted(): DefaultTransactionAttribute {
        val attribute = DefaultTransactionAttribute()
        attribute.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED

        return attribute
    }

    companion object {
        const val JOB_NAME = "weeklyRanking"
        const val FREEZE_STEP_NAME = "freeze"
        const val SCORE_STEP_NAME = "score"
        const val PUBLISH_STEP_NAME = "publish"
    }
}
