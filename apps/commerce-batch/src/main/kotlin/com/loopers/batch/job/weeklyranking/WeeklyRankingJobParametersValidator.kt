package com.loopers.batch.job.weeklyranking

import org.springframework.batch.core.job.parameters.InvalidJobParametersException
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersValidator
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Refuses `weeklyRanking`'s parameters unless all three identifying ones are there and usable: `periodStart` a Monday,
 * `snapshotId` not blank and no longer than its column, `revision` at least 1 (spec 237, Operator stories 2–3).
 *
 * A parameter of the wrong type is refused too, and not read leniently. Its type is part of what identifies the job instance,
 * so a `String` date would be another instance for the same week. The refusal shows the command-line spelling that gives the
 * right type.
 *
 * A refusal starts no job execution and freezes nothing. On Batch 6.0.6 it still leaves a job instance with no execution,
 * because the launcher creates the instance before it calls this (Spring Batch issue 5562, fixed in 6.1).
 *
 * It does not ask whether the period has ended. That would be a check that reads a clock, and a period frozen too early is
 * corrected by a later revision.
 */
class WeeklyRankingJobParametersValidator : JobParametersValidator {
    override fun validate(parameters: JobParameters) {
        val periodStart = required<LocalDate>(parameters, PERIOD_START)
        val snapshotId = required<String>(parameters, SNAPSHOT_ID)
        val revision = required<Long>(parameters, REVISION)

        if (periodStart.dayOfWeek != DayOfWeek.MONDAY) {
            throw InvalidJobParametersException("$PERIOD_START must be a Monday, but $periodStart is a ${periodStart.dayOfWeek}")
        }
        if (snapshotId.isBlank()) {
            throw InvalidJobParametersException("$SNAPSHOT_ID must not be blank")
        }
        if (snapshotId.length > SNAPSHOT_ID_MAX_LENGTH) {
            throw InvalidJobParametersException(
                "$SNAPSHOT_ID must be at most $SNAPSHOT_ID_MAX_LENGTH characters, but has ${snapshotId.length}",
            )
        }
        if (revision < 1) {
            throw InvalidJobParametersException("$REVISION must be at least 1, but was $revision")
        }
    }

    /** The value of [key], refused when it is missing or not a [T]. A wrong type is answered with the spelling of a [T]. */
    private inline fun <reified T : Any> required(parameters: JobParameters, key: String): T {
        val parameter = parameters.getParameter(key)
            ?: throw InvalidJobParametersException("Job parameter $key is missing")
        val value = parameter.value
        if (value !is T) {
            throw InvalidJobParametersException(
                "$key must be a ${T::class.simpleName}, but was a ${parameter.type.simpleName}. " +
                    "Give it as $key=$value,${T::class.javaObjectType.name}",
            )
        }

        return value
    }

    companion object {
        const val PERIOD_START = "periodStart"
        const val SNAPSHOT_ID = "snapshotId"
        const val REVISION = "revision"

        /** The length of `weekly_ranking_snapshot.snapshot_id` (`VARCHAR(100)`), so a longer ID fails here and not in the copy. */
        const val SNAPSHOT_ID_MAX_LENGTH = 100
    }
}
