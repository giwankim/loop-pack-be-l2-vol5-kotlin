package com.loopers.batch.job.weeklyranking

import com.loopers.batch.job.weeklyranking.step.FreezeTasklet
import com.loopers.batch.listener.JobListener
import com.loopers.batch.listener.StepMonitorListener
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.DefaultTransactionAttribute

/**
 * The weekly ranking job (`CONTEXT.md` 주간 랭킹). It is identified by three parameters an operator gives on the command line,
 * `periodStart` (`LocalDate`), `snapshotId` (`String`) and `revision` (`Long`), with no incrementer, run ID or clock value, so
 * a rerun with the same three is the same job instance. On this branch it ends after its one step, `freeze`.
 *
 * The step runs on the application's transaction manager, so its writes and its metadata commit together. Its transaction is
 * READ COMMITTED: the copy's `INSERT … SELECT` then takes no locks on `purchase_activity`, and the streamer can record a
 * purchase while a freeze runs (spec 237, 확인한 사실 1).
 */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration(proxyBeanMethods = false)
class WeeklyRankingJobConfig {
    @Bean(JOB_NAME)
    fun weeklyRankingJob(jobRepository: JobRepository, jobListener: JobListener, freezeStep: Step): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .validator(WeeklyRankingJobParametersValidator())
            .start(freezeStep)
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

    private fun readCommitted(): DefaultTransactionAttribute {
        val attribute = DefaultTransactionAttribute()
        attribute.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED

        return attribute
    }

    companion object {
        const val JOB_NAME = "weeklyRanking"
        const val FREEZE_STEP_NAME = "freeze"
    }
}
