package com.loopers.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/**
 * A run with no batch jar fails rather than skips, so zero tests never counts as a pass, as [CommerceStreamerProcessTest] shows for
 * the streamer. The check comes before any process starts, so no container is needed: the address names nothing.
 */
class CommerceBatchProcessTest {
    companion object {
        private val SETTINGS = CommerceBatchProcess.Settings(
            jdbcUrl = "jdbc:mysql://unused:3306/loopers",
            username = "unused",
            password = "unused",
        )

        private val PARAMETERS = CommerceBatchProcess.WeeklyRankingParameters(
            periodStart = LocalDate.parse("2026-08-17"),
            snapshotId = "2026-08-17-r1",
            revision = 1,
        )
    }

    @Test
    fun `a run without the jar property fails naming the property`() {
        val exception = assertThrows<AssertionError> { CommerceBatchProcess.run(SETTINGS, PARAMETERS, jarPath = null) }

        assertThat(exception.message).contains("commerce-batch.jar")
    }

    @Test
    fun `a run whose jar property names no file fails naming the property and the path`() {
        val exception = assertThrows<AssertionError> {
            CommerceBatchProcess.run(SETTINGS, PARAMETERS, jarPath = "build/libs/no-such-commerce-batch.jar")
        }

        assertThat(exception.message).contains("commerce-batch.jar").contains("build/libs/no-such-commerce-batch.jar")
    }
}
