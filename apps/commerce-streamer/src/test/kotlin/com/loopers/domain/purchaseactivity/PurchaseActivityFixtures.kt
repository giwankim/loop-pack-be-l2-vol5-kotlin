package com.loopers.domain.purchaseactivity

import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import org.instancio.kotlin.KInstancio
import org.instancio.kotlin.KInstancio.gen
import org.instancio.kotlin.KSelect.field
import java.time.Instant
import java.time.temporal.ChronoUnit

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

/**
 * A recording request for one order confirmed event. Instancio builds it, as every request (ADR 0010), and the fixture sets
 * every field. The order ID is drawn as [createPurchaseActivity] draws it, and the event ID is derived from it as
 * commerce-api derives it (order events design 2), so requests built apart never share either. The confirmation time is in
 * the past and in the microseconds commerce-api publishes, so the time a test sends is the one the entities keep. There are
 * 1..5 items of drawn products, each with 1..10: the quantity has no upper bound in the request, and a drawn product ID
 * repeats in one request with negligible odds.
 */
fun createPurchaseActivityRecordRequest(
    eventId: String? = null,
    orderId: Long? = null,
    confirmedAt: Instant? = null,
    items: List<PurchaseActivityRecordRequest.Item>? = null,
): PurchaseActivityRecordRequest {
    val fixedOrderId = orderId ?: gen().longs().range(1, Long.MAX_VALUE).get()
    val fixedItems = items ?: List(gen().ints().range(1, 5).get()) {
        KInstancio.of<PurchaseActivityRecordRequest.Item>()
            .set(field(PurchaseActivityRecordRequest.Item::productId), gen().longs().range(1, Long.MAX_VALUE).get())
            .set(field(PurchaseActivityRecordRequest.Item::quantity), gen().ints().range(1, 10).get())
            .create()
    }

    return KInstancio.of<PurchaseActivityRecordRequest>()
        .set(field(PurchaseActivityRecordRequest::eventId), eventId ?: "order-confirmed:v1:$fixedOrderId")
        .set(field(PurchaseActivityRecordRequest::orderId), fixedOrderId)
        .set(
            field(PurchaseActivityRecordRequest::confirmedAt),
            confirmedAt ?: gen().temporal().instant().past().truncatedTo(ChronoUnit.MICROS).get(),
        )
        .set(field(PurchaseActivityRecordRequest::items), fixedItems)
        .create()
}
