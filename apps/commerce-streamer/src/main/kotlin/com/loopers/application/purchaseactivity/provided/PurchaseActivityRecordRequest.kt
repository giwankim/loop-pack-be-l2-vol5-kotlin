package com.loopers.application.purchaseactivity.provided

import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import java.time.Instant

/**
 * One order confirmed event, as the listener hands it to [PurchaseActivityRecorder] (purchase activity design 2). commerce-api
 * derives the event ID from the order ID, and the confirmation time comes in the microseconds it publishes.
 *
 * Bean Validation sits on this request and nowhere else on the event's way in: the items are not empty, each quantity is
 * positive, and each product appears once. A request that breaks one of these is an unprocessable event (`CONTEXT.md` 처리할
 * 수 없는 이벤트), refused before anything is read or written. The activity's constructor refuses a non-positive quantity too.
 */
data class PurchaseActivityRecordRequest(
    val eventId: String,
    val orderId: Long,
    val confirmedAt: Instant,
    @Valid
    @NotEmpty(message = "An order confirmed event must have at least one item.")
    val items: List<Item>,
) {
    /** One activity per order and product, so a product named twice has no activity to become (ADR 0033 rule 2). */
    @get:AssertTrue(message = "An order confirmed event must name each product once.")
    val isEachProductOnce: Boolean
        get() {
            return items.distinctBy { it.productId }.size == items.size
        }

    data class Item(
        val productId: Long,
        @Positive(message = "An item's quantity must be positive.")
        val quantity: Int,
    )
}
