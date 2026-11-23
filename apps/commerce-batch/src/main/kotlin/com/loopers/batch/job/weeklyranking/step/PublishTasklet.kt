package com.loopers.batch.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.PERIOD_START
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.REVISION
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.SNAPSHOT_ID
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.batch.core.ExitStatus
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
 * A snapshot whose ranking already holds as many items as it has scores is not ranked again, and only its publication is
 * written: the path of a rerun after a publish that committed under an execution recorded as failed. A publication the write
 * leaves in place, the same or a later revision's, completes the step all the same, and the log and the step's exit
 * description say it was not moved: the snapshot's ranking has nothing wrong, and a failure would leave a `JobInstance` no
 * rerun could complete (spec 252).
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
        val itemCount = publishStore.countRankingItems(snapshotId)
        if (itemCount > 0 && itemCount == scoreCount) {
            log.info { "${key.describe()} is already ranked with $itemCount items, so only its publication is written" }
        } else {
            rank(key, scoreCount)
        }

        val publishedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)

        val publication = publishStore.publish(key, publishedAt)
        if (!publication.isWrittenBy(key, publishedAt)) {
            val notMoved = "Publication of period $periodStart not moved to ${key.describe()}: it holds ${publication.describe()}"
            log.warn { notMoved }
            contribution.exitStatus = ExitStatus.COMPLETED.addExitDescription(notMoved)

            return RepeatStatus.FINISHED
        }

        log.info { "Published ${key.describe()} at $publishedAt" }

        return RepeatStatus.FINISHED
    }

    /** Ranks [key]'s scores, once they are checked to cover every product of its inputs. */
    private fun rank(key: SnapshotKey, scoreCount: Long) {
        val productCount = publishStore.countInputProducts(key.snapshotId)
        check(scoreCount == productCount) {
            "${key.describe()} has $scoreCount scores for $productCount products, so its ranking is incomplete"
        }

        publishStore.rankScores(key.snapshotId)
        log.info { "Ranked $scoreCount products of ${key.describe()}" }
    }
}
