package com.loopers.domain.purchaseactivity

import org.instancio.kotlin.KInstancio.gen
import java.time.Instant

/**
 * An unsaved purchase activity. The real constructor builds it, because the constructor cuts the confirmation time to
 * microseconds, and Instancio would skip that (ADR 0010). The order and product IDs are values in this context, not other
 * aggregates, so the fixture draws them: positive across the whole `Long` range, so activities built apart never share an
 * order and product pair. The quantity is 1..10, as commerce-api's order line items are. The confirmation time is in the past.
 */
fun createPurchaseActivity(
    orderId: Long? = null,
    productId: Long? = null,
    quantity: Int? = null,
    confirmedAt: Instant? = null,
): PurchaseActivity {
    return PurchaseActivity(
        orderId = orderId ?: gen().longs().range(1, Long.MAX_VALUE).get(),
        productId = productId ?: gen().longs().range(1, Long.MAX_VALUE).get(),
        quantity = quantity ?: gen().ints().range(1, 10).get(),
        confirmedAt = confirmedAt ?: gen().temporal().instant().past().get(),
    )
}
