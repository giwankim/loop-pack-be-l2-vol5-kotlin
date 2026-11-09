package com.loopers.adapter.scheduling

import com.loopers.application.order.provided.OrderEventRelayer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Runs the relay by itself on the one instance that turns it on (ADR 0035 rule 13). `order-events.relay.enabled` is a setting, not
 * ownership. It's off by default, on under `local` and off under `test`, where scenes run rounds by hand. The condition covers
 * `@EnableScheduling` too, so an instance with the relay off has no scheduling at all.
 *
 * `fixedDelay` counts from the end of a round, and Boot's scheduler has one thread, so rounds never overlap. A round's exception reaches
 * Spring's error handler, which logs it and keeps the schedule: the next tick is the retry, and the rows it left come due with their leases.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = ["order-events.relay.enabled"], havingValue = "true")
class OrderEventRelayScheduler(
    private val orderEventRelayer: OrderEventRelayer,
) {
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    fun publishDueEvents() {
        orderEventRelayer.publishDueEvents()
    }
}
