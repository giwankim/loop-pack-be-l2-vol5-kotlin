package com.loopers.application.order

import com.loopers.application.order.provided.OrderEventRecoverer
import com.loopers.application.order.provided.OrderEventRecoveryResult
import com.loopers.support.stereotype.ApplicationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.transaction.annotation.Propagation

private val log = KotlinLogging.logger {}

/**
 * [OrderEventRecoverer]'s implementation: one transaction per order, in the given order, each in [OrderEventOutboxModifyService]
 * (ADR 0036 rule 3). It only sequences the calls and logs what each did once that order's transaction has committed. `NEVER` makes
 * Spring refuse a call inside a transaction, so the orders can't quietly share one.
 */
@ApplicationService(propagation = Propagation.NEVER)
class OrderEventRecovery(
    private val orderEventOutboxModifyService: OrderEventOutboxModifyService,
) : OrderEventRecoverer {
    override fun recover(orderIds: List<Long>): List<OrderEventRecoveryResult> {
        return orderIds.map { orderId -> recoverOne(orderId) }
    }

    private fun recoverOne(orderId: Long): OrderEventRecoveryResult {
        val recoveryResult = orderEventOutboxModifyService.recover(orderId)

        log.info {
            "Recovered the order confirmed event. orderId=${recoveryResult.orderId}, eventId=${recoveryResult.eventId}, " +
                "result=${recoveryResult.outcome}"
        }

        return recoveryResult
    }
}
