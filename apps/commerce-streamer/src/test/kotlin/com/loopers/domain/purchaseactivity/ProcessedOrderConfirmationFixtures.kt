package com.loopers.domain.purchaseactivity

import org.instancio.kotlin.KInstancio.gen
import java.time.Instant

/**
 * An unsaved processed order confirmation. The real constructor builds it, because the constructor cuts the confirmation time
 * to microseconds, and Instancio would skip that (ADR 0010). The order ID is a value in this context, not another aggregate,
 * so the fixture draws it: positive across the whole `Long` range, so confirmations built apart never share an order. The
 * event ID is derived from the order ID as commerce-api derives it (order events design 2), so confirmations built apart never
 * share one either. The confirmation time is in the past.
 */
fun createProcessedOrderConfirmation(
    eventId: String? = null,
    orderId: Long? = null,
    confirmedAt: Instant? = null,
): ProcessedOrderConfirmation {
    val fixedOrderId = orderId ?: gen().longs().range(1, Long.MAX_VALUE).get()

    return ProcessedOrderConfirmation(
        eventId = eventId ?: "order-confirmed:v1:$fixedOrderId",
        orderId = fixedOrderId,
        confirmedAt = confirmedAt ?: gen().temporal().instant().past().get(),
    )
}
