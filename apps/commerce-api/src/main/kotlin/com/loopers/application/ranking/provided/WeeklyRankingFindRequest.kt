package com.loopers.application.ranking.provided

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.format.annotation.DateTimeFormat
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The input of the weekly ranking read: which ranking period, by its start date, and how many items at most.
 *
 * A ranking period starts on a UTC Monday (the weekly ranking glossary, `apps/commerce-batch/CONTEXT.md`), so any other date names no
 * period and is refused rather than answered as an empty ranking. The check is a constraint here, so both the controller's `@Valid` and
 * the query service's method validation run it: a caller that skips the controller is refused too (spec 252).
 *
 * [periodStart] is nullable only so that a missing value reaches [NotNull] and its message: a non-null constructor parameter fails
 * the binder with Kotlin's own null check instead, which names no field. Past validation it is never null.
 *
 * The bounds of [limit] are the product list's: 100 at most, so the API decides how much one read returns.
 */
data class WeeklyRankingFindRequest(
    @NotNull(message = "periodStart는 필수입니다.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val periodStart: LocalDate?,
    @Min(1, message = "limit는 1 이상이어야 합니다.")
    @Max(MAX_LIMIT.toLong(), message = "limit는 {value} 이하여야 합니다.")
    val limit: Int = DEFAULT_LIMIT,
) {
    @get:AssertTrue(message = "periodStart는 월요일이어야 합니다.")
    val isPeriodStartMonday: Boolean
        get() {
            // A missing value is NotNull's refusal, not this one's.
            return periodStart == null || periodStart.dayOfWeek == DayOfWeek.MONDAY
        }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 100
    }
}
