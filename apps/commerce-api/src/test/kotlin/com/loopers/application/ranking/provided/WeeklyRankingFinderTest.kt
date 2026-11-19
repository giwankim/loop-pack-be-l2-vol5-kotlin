package com.loopers.application.ranking.provided

import com.loopers.support.test.BaseApplicationServiceTest
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/**
 * The weekly ranking read called without the controller, as another slice or a batch would call it. The web scenes are in
 * [com.loopers.adapter.webapi.v1.ranking.WeeklyRankingApiTest]; this one shows the Monday check does not depend on them (spec 252).
 */
class WeeklyRankingFinderTest(
    private val weeklyRankingFinder: WeeklyRankingFinder,
) : BaseApplicationServiceTest() {
    @Test
    fun `the query service refuses a periodStart that is not a Monday without the controller`() {
        val exception = assertThrows<ConstraintViolationException> {
            weeklyRankingFinder.find(WeeklyRankingFindRequest(periodStart = LocalDate.parse("2026-08-18")))
        }

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("periodStart는 월요일이어야 합니다.")
    }
}
