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
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val log = KotlinLogging.logger {}

/**
 * Freezes one ranking period's purchase activity into a snapshot (`CONTEXT.md` 고정하다). The step runs it in one transaction,
 * so the inputs and the snapshot commit together, and a freeze that fails partway leaves neither.
 *
 * A snapshot that already holds the snapshot ID, or the period and revision, refuses the freeze before anything is written
 * (`CONTEXT.md` 충돌). The table's primary and unique keys refuse the same rows, should the check be passed.
 *
 * The frozen-at time is read once, before the copy, and cut to the microseconds the column keeps. The period is a UTC week
 * from [periodStart], whatever the JVM's zone.
 */
@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
class FreezeTasklet(
    @param:Value("#{jobParameters['$PERIOD_START']}") private val periodStart: LocalDate,
    @param:Value("#{jobParameters['$SNAPSHOT_ID']}") private val snapshotId: String,
    @param:Value("#{jobParameters['$REVISION']}") private val revision: Long,
    private val snapshotStore: WeeklyRankingSnapshotStore,
    private val clock: Clock,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val key = SnapshotKey(snapshotId, periodStart, revision)
        val conflicting = snapshotStore.findConflicting(key)
        if (conflicting.isNotEmpty()) {
            throw SnapshotConflictException(key, conflicting)
        }

        val frozenAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val start = periodStart.atStartOfDay(ZoneOffset.UTC).toInstant()
        val end = start.plus(PERIOD_LENGTH)

        snapshotStore.copyInputs(snapshotId, start, end)
        val totals = snapshotStore.digestInputs(snapshotId)
        snapshotStore.insertFrozenSnapshot(key, frozenAt, totals)
        log.info {
            "Froze period $periodStart revision $revision as $snapshotId: ${totals.count} inputs, checksum ${totals.checksum}"
        }

        return RepeatStatus.FINISHED
    }

    companion object {
        private val PERIOD_LENGTH: Duration = Duration.ofDays(7)
    }
}
