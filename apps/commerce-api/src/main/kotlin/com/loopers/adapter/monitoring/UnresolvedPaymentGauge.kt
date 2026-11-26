package com.loopers.adapter.monitoring

import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.payment.provided.UnresolvedPaymentCount
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Reports the number of 끝나지 않은 결제 as the gauge `commerce.payment.unresolved`, one series per payment status (spec 266).
 * The value comes from the payment rows: a scheduled refresh counts them through [PaymentFinder] and stores the count, and a
 * scrape reads the stored count. A scrape runs no query, and nothing raises or lowers the number in memory, so the value
 * survives a restart and every instance reports the same database-wide count.
 *
 * Before the first good refresh and after a failed one both series are `NaN`, so a database outage never reads as "0 unresolved".
 * The refresh catches its own failure and logs a WARN, and the next tick counts again.
 *
 * `payment.unresolved-gauge.enabled` covers `@EnableScheduling` and the registration: on by default, off under `test`, where
 * other scenes' SQL captures must see no background query. Unlike the relay, every instance turns it on. The interval
 * `payment.unresolved-gauge.refresh-interval` counts from the end of the previous refresh. Boot's scheduler has one thread, so on
 * the instance that also runs the relay a long relay round delays the refresh.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = ["payment.unresolved-gauge.enabled"], havingValue = "true", matchIfMissing = true)
class UnresolvedPaymentGauge(
    private val paymentFinder: PaymentFinder,
    meterRegistry: MeterRegistry,
) {
    companion object {
        const val NAME = "commerce.payment.unresolved"

        private const val DESCRIPTION = "The number of unresolved payments (UNKNOWN or PENDING), by payment status. " +
            "Counted from the payment rows once per refresh interval, so the value can be one interval old. " +
            "Every instance reports the same database-wide count: aggregate with max, not sum."
    }

    /** The last good count, or `null` before the first good refresh and after a failed one. */
    private val count = AtomicReference<UnresolvedPaymentCount?>(null)

    init {
        register(meterRegistry, "UNKNOWN") { it.unknown }
        register(meterRegistry, "PENDING") { it.pending }
    }

    @Scheduled(fixedDelayString = "\${payment.unresolved-gauge.refresh-interval:10s}")
    fun refresh() {
        try {
            count.set(paymentFinder.countUnresolved())
        } catch (exception: Exception) {
            count.set(null)
            log.warn(exception) { "Counting unresolved payments failed; the gauge reports NaN until the next good count." }
        }
    }

    private fun register(meterRegistry: MeterRegistry, status: String, value: (UnresolvedPaymentCount) -> Long) {
        Gauge.builder(NAME) { valueOf(value) }
            .description(DESCRIPTION)
            .tag("status", status)
            .register(meterRegistry)
    }

    /** [value] of the last good count, or `NaN` when there is none. */
    private fun valueOf(value: (UnresolvedPaymentCount) -> Long): Double {
        val last = count.get() ?: return Double.NaN

        return value(last).toDouble()
    }
}
