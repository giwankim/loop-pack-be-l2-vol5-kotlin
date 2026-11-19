package com.loopers.batch.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.PERIOD_START
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.REVISION
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.SNAPSHOT_ID
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val log = KotlinLogging.logger {}

/**
 * Ranks a snapshot's scores and publishes them as its period's weekly ranking (`CONTEXT.md` 순위, 게시하다). The step runs it
 * in one transaction, so the check, the ranking and the publication commit together, and a publish that fails partway leaves
 * neither the ranking nor a moved publication.
 *
 * The snapshot's state stays `FROZEN`: what a period shows is its publication row alone (spec 252).
 */
@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
class PublishTasklet(
    @param:Value("#{jobParameters['$PERIOD_START']}") private val periodStart: LocalDate,
    @param:Value("#{jobParameters['$SNAPSHOT_ID']}") private val snapshotId: String,
    @param:Value("#{jobParameters['$REVISION']}") private val revision: Long,
    private val publishStore: WeeklyRankingPublishStore,
    private val clock: Clock,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val key = SnapshotKey(snapshotId, periodStart, revision)
        val scoreCount = publishStore.countScores(snapshotId)
        val productCount = publishStore.countInputProducts(snapshotId)
        check(scoreCount == productCount) {
            "${key.describe()} has $scoreCount scores for $productCount products, so its ranking is incomplete"
        }

        publishStore.rankScores(snapshotId)
        val publishedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        publishStore.publish(key, publishedAt)
        log.info { "Ranked $scoreCount products of ${key.describe()} and published it at $publishedAt" }

        return RepeatStatus.FINISHED
    }
}
