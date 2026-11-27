package com.loopers.adapter.monitoring

import com.loopers.application.order.required.PaymentStatusChangeListener
import com.loopers.domain.payment.PaymentStatus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Reports the number of 끝나지 않은 결제 as the gauge `commerce.payment.unresolved`, one series per payment status (spec 281).
 * The number lives in this process's memory: every committed status change the saga reports raises the series of the status a
 * payment entered and lowers the series of the one it left. Only 결과 미확인 and 처리 중 have a series, and this adapter alone
 * decides that. A scrape reads the two numbers and runs no query.
 *
 * The value is what this process has seen since it started. A restart starts both series at 0 and seeds nothing from the database,
 * and a payment this process never saw start (begun before a restart, or on another instance) takes its series below 0 when it is
 * resolved here. The negative number is reported as it is, so the instances' values add up: aggregate them with `sum`.
 */
@Component
class UnresolvedPaymentGauge(
    meterRegistry: MeterRegistry,
) : PaymentStatusChangeListener {
    companion object {
        const val NAME = "commerce.payment.unresolved"

        private const val DESCRIPTION = "The number of unresolved payments (UNKNOWN or PENDING), by payment status. " +
            "Counts what this process has seen since this process started, so an instance can be negative. " +
            "Each instance counts its own: aggregate with sum."

        private val UNRESOLVED_STATUSES = listOf(PaymentStatus.UNKNOWN, PaymentStatus.PENDING)
    }

    private val counts: Map<PaymentStatus, AtomicLong> = UNRESOLVED_STATUSES.associateWith { AtomicLong() }

    init {
        counts.forEach { (status, count) ->
            Gauge.builder(NAME, count) { it.get().toDouble() }
                .description(DESCRIPTION)
                .tag("status", status.name)
                .register(meterRegistry)
        }
    }

    override fun statusChanged(from: PaymentStatus?, to: PaymentStatus) {
        if (from != null) {
            counts[from]?.decrementAndGet()
        }

        counts[to]?.incrementAndGet()
    }
}
