package com.loopers.adapter.startup

import com.loopers.application.order.provided.OrderEventRecoverer
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 누락 복구's entry point (ADR 0036 rule 2): a person names orders in `order-events.recovery.order-ids` and restarts the app, and this
 * hands them to the port once at startup. There is no endpoint. Empty by default, it calls nothing. Like
 * [com.loopers.adapter.scheduling.OrderEventRelayScheduler] it decides nothing: the port's refusal propagates, and the start fails
 * with it once Spring has closed the context, so a refused order can't go unnoticed.
 *
 * The property is read through a [Binder], so it takes a comma-separated value (`--order-events.recovery.order-ids=3,7`, or the
 * `ORDER_EVENTS_RECOVERY_ORDER_IDS` variable) and a YAML list alike.
 */
@Component
class OrderEventRecoveryRunner(
    private val orderEventRecoverer: OrderEventRecoverer,
    private val environment: Environment,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val orderIds = Binder.get(environment)
            .bind("order-events.recovery.order-ids", Bindable.listOf(Long::class.javaObjectType))
            .orElse(null)
            .orEmpty()

        if (orderIds.isEmpty()) {
            return
        }

        orderEventRecoverer.recover(orderIds)
    }
}
