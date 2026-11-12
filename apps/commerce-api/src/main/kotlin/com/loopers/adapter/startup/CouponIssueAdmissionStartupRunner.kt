package com.loopers.adapter.startup

import com.loopers.application.coupon.provided.CouponIssueAdmissionOpener
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * Opens the coupon issue admission once, after the application has started, when `coupon-issue.admission-open-on-startup` is `true`.
 * Unset, it is `false` and admission stays closed. `application.yml` sets it for every profile, so a restart is what opens a closed
 * instance in production; nothing in a running app opens it again, and there is no endpoint.
 *
 * Unlike [OrderEventRecoveryRunner], a failure does not fail the start: a Redis outage at startup must not take ordering and payments
 * down with the coupon issue. It logs an ERROR and the app runs with admission closed.
 */
@Component
class CouponIssueAdmissionStartupRunner(
    private val couponIssueAdmissionOpener: CouponIssueAdmissionOpener,
    private val environment: Environment,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val openOnStartup = environment.getProperty(OPEN_ON_STARTUP, Boolean::class.javaObjectType, false)
        if (!openOnStartup) {
            return
        }

        try {
            couponIssueAdmissionOpener.open()
        } catch (exception: Exception) {
            log.error(exception) { "Could not open coupon issue admission at startup; it stays closed until a restart" }
        }
    }

    companion object {
        const val OPEN_ON_STARTUP = "coupon-issue.admission-open-on-startup"
    }
}
