package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponIssueAdmissionOpenRefusedException
import com.loopers.application.coupon.provided.CouponIssueAdmissionOpener
import com.loopers.application.coupon.required.CouponIssueSlots
import com.loopers.support.stereotype.ApplicationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.transaction.annotation.Propagation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

/**
 * The coupon issue admission (`CONTEXT.md`'s 발급 수용): whether this instance starts new issues. It stands between
 * [CouponIssueService] and the [CouponIssueSlots] port, and only it calls the port. The state lives in this process, one per
 * instance, and is never written to Redis or the database.
 *
 * - Admission starts closed. Only [open] opens it, and nothing in a running app calls that: the startup does, once.
 * - [admit] counts the issue as running from before its acquire until after its release attempt, so an acquire in flight is running
 *   too and [open] never removes the token it is about to get.
 * - Closed, [admit] refuses with [CouponIssueAdmissionClosedException] and calls no port.
 * - The port answering `null` is full capacity: [CouponIssueCapacityExceededException], and admission stays open.
 * - The port failing on acquire closes admission and refuses with [CouponIssueAdmissionClosedException]: the acquire may or may not
 *   have reached Redis, so the Set can no longer be trusted. No issuing transaction starts and nothing retries.
 * - The port failing on release, or answering that the held token was gone, closes admission. The failure is swallowed, so the
 *   request answers with the issuing transaction's outcome: a committed coupon is never reported as a failure.
 * - Closing never stops an issue that already holds a slot. It finishes and releases its own slot.
 * - Closing logs one ERROR with its cause, at the moment admission turns closed. Refusals while closed log nothing here.
 *
 * Errors only ever make it admit less: a leftover token, a closed instance. Nothing here issues a coupon the issuing transaction
 * would not.
 */
@ApplicationService(propagation = Propagation.NEVER)
class CouponIssueAdmission(
    private val couponIssueSlots: CouponIssueSlots,
) : CouponIssueAdmissionOpener {
    private val open = AtomicBoolean(false)

    private val runningIssues = AtomicInteger(0)

    /** Runs [issue] in a processing slot, or refuses before it starts. [issue] runs no transaction of this call's. */
    fun <T> admit(issue: () -> T): T {
        runningIssues.incrementAndGet()
        try {
            val token = acquire()
            try {
                return issue()
            } finally {
                release(token)
            }
        } finally {
            runningIssues.decrementAndGet()
        }
    }

    /**
     * The order of checks matters together with [admit]'s count: an issue counts itself running before it reads the state, and this
     * reads the count before it removes anything. So an issue either is counted here and refuses the opening, or reads the state only
     * after it was set, which is after the tokens were removed.
     */
    override fun open() {
        if (open.get()) {
            return
        }

        val running = runningIssues.get()
        if (running > 0) {
            throw CouponIssueAdmissionOpenRefusedException(running)
        }

        couponIssueSlots.releaseOwnTokens()
        open.set(true)
        log.info { "Opened coupon issue admission after removing this instance's processing slot tokens" }
    }

    private fun acquire(): String {
        if (!open.get()) {
            throw CouponIssueAdmissionClosedException()
        }

        val token = try {
            couponIssueSlots.acquire()
        } catch (exception: Exception) {
            close("acquiring a processing slot failed", exception)
            throw CouponIssueAdmissionClosedException(exception)
        }

        return token ?: throw CouponIssueCapacityExceededException()
    }

    private fun release(token: String) {
        val released = try {
            couponIssueSlots.release(token)
        } catch (exception: Exception) {
            close("releasing processing slot $token failed", exception)
            return
        }

        if (!released) {
            close("processing slot $token was gone from the Set when its issue released it", cause = null)
        }
    }

    private fun close(reason: String, cause: Exception?) {
        if (open.compareAndSet(true, false)) {
            log.error(cause) { "Closed coupon issue admission: $reason. It stays closed until this instance restarts." }
        }
    }
}
