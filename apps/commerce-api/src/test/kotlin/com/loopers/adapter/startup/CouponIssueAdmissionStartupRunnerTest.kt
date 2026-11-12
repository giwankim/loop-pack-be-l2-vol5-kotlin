package com.loopers.adapter.startup

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.loopers.application.coupon.provided.CouponIssueAdmissionOpener
import com.loopers.support.linesAt
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment

/**
 * The runner by itself, over an environment the scene sets. Every context of the suite runs it with `application.yml`'s `true`, and
 * `CouponIssueAdmissionApiTest` starts one with `false`; what neither shows is a missing property and a failing opening. No scene
 * starts a context with Redis stopped: the failing opening here is the opener throwing (`docs/design/coupons.md` 11.10).
 */
class CouponIssueAdmissionStartupRunnerTest {
    private val opener = mockk<CouponIssueAdmissionOpener>()

    private val runnerLogs = ListAppender<ILoggingEvent>()

    private val runnerLogger = LoggerFactory.getLogger(CouponIssueAdmissionStartupRunner::class.java) as Logger

    @BeforeEach
    fun captureLogs() {
        runnerLogs.start()
        runnerLogger.addAppender(runnerLogs)
    }

    @AfterEach
    fun releaseLogs() {
        runnerLogger.detachAppender(runnerLogs)
        runnerLogs.stop()
    }

    @Test
    fun `with the property absent, the runner leaves admission closed`() {
        CouponIssueAdmissionStartupRunner(opener, MockEnvironment()).run(DefaultApplicationArguments())

        verify(exactly = 0) { opener.open() }
    }

    @Test
    fun `with the property true, the runner opens admission once`() {
        every { opener.open() } just runs

        CouponIssueAdmissionStartupRunner(opener, environmentOpeningOnStartup()).run(DefaultApplicationArguments())

        verify(exactly = 1) { opener.open() }
    }

    @Test
    fun `an opening that fails logs an error and does not fail the start`() {
        every { opener.open() } throws IllegalStateException("Redis did not answer")

        CouponIssueAdmissionStartupRunner(opener, environmentOpeningOnStartup()).run(DefaultApplicationArguments())

        val errors = runnerLogs.linesAt(Level.ERROR)
        assertThat(errors).hasSize(1)
        assertThat(errors.single().throwableProxy.message).isEqualTo("Redis did not answer")
    }

    private fun environmentOpeningOnStartup(): MockEnvironment {
        return MockEnvironment().withProperty("coupon-issue.admission-open-on-startup", "true")
    }
}
