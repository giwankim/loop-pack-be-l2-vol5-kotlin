package com.loopers.application.purchaseactivity.provided

import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest.Item
import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmation
import com.loopers.domain.purchaseactivity.PurchaseActivity
import com.loopers.domain.purchaseactivity.createPurchaseActivityRecordRequest
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Checks [PurchaseActivityRecorder] on a real MySQL: a new event, the same fact again, each kind of conflict, and the request
 * validation (ADR 0033 rules 3 and 4). The recorder returns only what it did, so the rows are read from the two tables after a
 * flush and clear. "No row changes" compares every row of both, with its ID, before and after the call.
 */
class PurchaseActivityRecorderTest(
    private val purchaseActivityRecorder: PurchaseActivityRecorder,
) : BaseApplicationServiceTest() {
    @Test
    fun `a new event is recorded as one processed confirmation and one activity per item at its confirmation time`() {
        val request = createPurchaseActivityRecordRequest()

        val result = purchaseActivityRecorder.record(request)

        assertThat(result).isEqualTo(PurchaseActivityRecordResult.RECORDED)
        assertThat(storedConfirmations()).singleElement().satisfies({ confirmation ->
            assertThat(confirmation.eventId).isEqualTo(request.eventId)
            assertThat(confirmation.orderId).isEqualTo(request.orderId)
            assertThat(confirmation.confirmedAt).isEqualTo(request.confirmedAt)
        })
        assertThat(storedActivities().map { listOf(it.orderId, it.productId, it.quantity, it.confirmedAt) })
            .containsExactlyInAnyOrderElementsOf(
                request.items.map { listOf(request.orderId, it.productId, it.quantity, request.confirmedAt) },
            )
    }

    @Test
    fun `the same event again is already recorded and adds no row`() {
        val recorded = prepareRecordedEvent()
        val rowsBefore = storedRows()

        val result = purchaseActivityRecorder.record(recorded)

        assertThat(result).isEqualTo(PurchaseActivityRecordResult.ALREADY_RECORDED)
        assertThat(storedRows()).isEqualTo(rowsBefore)
    }

    /** The items' order is not part of the fact: they compare as a set of products and quantities (purchase activity design 4). */
    @Test
    fun `the same event with its items in another order is already recorded`() {
        val recorded = prepareRecordedEvent(items = listOf(Item(productId = 1, quantity = 2), Item(productId = 2, quantity = 1)))
        val rowsBefore = storedRows()
        val incoming = recorded.copy(items = listOf(Item(productId = 2, quantity = 1), Item(productId = 1, quantity = 2)))

        val result = purchaseActivityRecorder.record(incoming)

        assertThat(result).isEqualTo(PurchaseActivityRecordResult.ALREADY_RECORDED)
        assertThat(storedRows()).isEqualTo(rowsBefore)
    }

    /**
     * The entities cut the time to microseconds, so the recorded time is `…03.123456Z`. The event compares as the time it
     * would be stored as, or the same fact sent again would conflict with itself.
     */
    @Test
    fun `the same event with a time finer than microseconds is already recorded`() {
        val recorded = prepareRecordedEvent(confirmedAt = Instant.parse("2026-10-27T01:02:03.123456789Z"))
        val rowsBefore = storedRows()

        val result = purchaseActivityRecorder.record(recorded)

        assertThat(result).isEqualTo(PurchaseActivityRecordResult.ALREADY_RECORDED)
        assertThat(storedRows()).isEqualTo(rowsBefore)
    }

    @Test
    fun `the same event id with another quantity conflicts and changes no row`() {
        val recorded = prepareRecordedEvent(items = listOf(Item(productId = 1, quantity = 2), Item(productId = 2, quantity = 1)))
        val rowsBefore = storedRows()
        val incoming = recorded.copy(items = listOf(Item(productId = 1, quantity = 3), Item(productId = 2, quantity = 1)))

        val exception = assertThrows<PurchaseActivityConflictException> { purchaseActivityRecorder.record(incoming) }

        assertThat(exception).hasMessage(
            "Order confirmed event ${recorded.eventId} of order ${recorded.orderId} conflicts with the recorded one: " +
                "items (product=quantity) recorded {1=2, 2=1}, incoming {1=3, 2=1}.",
        )
        assertThat(storedRows()).isEqualTo(rowsBefore)
    }

    @Test
    fun `the same event id with another set of products conflicts and changes no row`() {
        val recorded = prepareRecordedEvent(items = listOf(Item(productId = 1, quantity = 2), Item(productId = 2, quantity = 1)))
        val rowsBefore = storedRows()
        val incoming = recorded.copy(items = listOf(Item(productId = 1, quantity = 2), Item(productId = 3, quantity = 1)))

        val exception = assertThrows<PurchaseActivityConflictException> { purchaseActivityRecorder.record(incoming) }

        assertThat(exception).hasMessage(
            "Order confirmed event ${recorded.eventId} of order ${recorded.orderId} conflicts with the recorded one: " +
                "items (product=quantity) recorded {1=2, 2=1}, incoming {1=2, 3=1}.",
        )
        assertThat(storedRows()).isEqualTo(rowsBefore)
    }

    /** One microsecond is the smallest difference the column keeps. */
    @Test
    fun `the same event id with another confirmation time conflicts and changes no row`() {
        val recorded = prepareRecordedEvent(confirmedAt = Instant.parse("2026-10-27T01:02:03.123456Z"))
        val rowsBefore = storedRows()
        val incoming = recorded.copy(confirmedAt = Instant.parse("2026-10-27T01:02:03.123457Z"))

        val exception = assertThrows<PurchaseActivityConflictException> { purchaseActivityRecorder.record(incoming) }

        assertThat(exception).hasMessage(
            "Order confirmed event ${recorded.eventId} of order ${recorded.orderId} conflicts with the recorded one: " +
                "confirmation time recorded 2026-10-27T01:02:03.123456Z, incoming 2026-10-27T01:02:03.123457Z.",
        )
        assertThat(storedRows()).isEqualTo(rowsBefore)
    }

    /** The items are the recorded order's, and they are the same, so the order ID is the only difference. */
    @Test
    fun `the same event id with another order conflicts and changes no row`() {
        val recorded = prepareRecordedEvent(eventId = "order-confirmed:v1:1", orderId = 1)
        val rowsBefore = storedRows()
        val incoming = recorded.copy(orderId = 2)

        val exception = assertThrows<PurchaseActivityConflictException> { purchaseActivityRecorder.record(incoming) }

        assertThat(exception).hasMessage(
            "Order confirmed event order-confirmed:v1:1 of order 2 conflicts with the recorded one: " +
                "order ID recorded 1, incoming 2.",
        )
        assertThat(storedRows()).isEqualTo(rowsBefore)
    }

    /** No confirmation has the event ID, but one has the order, so the event ID is the difference. */
    @Test
    fun `the same order under another event id conflicts and changes no row`() {
        val recorded = prepareRecordedEvent(eventId = "order-confirmed:v1:1", orderId = 1)
        val rowsBefore = storedRows()
        val incoming = recorded.copy(eventId = "order-confirmed:v2:1")

        val exception = assertThrows<PurchaseActivityConflictException> { purchaseActivityRecorder.record(incoming) }

        assertThat(exception).hasMessage(
            "Order confirmed event order-confirmed:v2:1 of order 1 conflicts with the recorded one: " +
                "event ID recorded order-confirmed:v1:1, incoming order-confirmed:v2:1.",
        )
        assertThat(storedRows()).isEqualTo(rowsBefore)
    }

    @Test
    fun `an event without items is refused by request validation and writes nothing`() {
        val request = createPurchaseActivityRecordRequest(items = emptyList())

        assertThat(violationsOf(request)).containsExactly("An order confirmed event must have at least one item.")
        assertThat(storedRows()).isEmpty()
    }

    /** The constraint inside an item is reached through the `@Valid` on [PurchaseActivityRecordRequest.items]. */
    @Test
    fun `an event with a zero quantity is refused by request validation and writes nothing`() {
        val request = createPurchaseActivityRecordRequest(items = listOf(Item(productId = 1, quantity = 0)))

        assertThat(violationsOf(request)).containsExactly("An item's quantity must be positive.")
        assertThat(storedRows()).isEmpty()
    }

    /** Without the refusal, the activities' unique key would refuse the second item after the confirmation was inserted. */
    @Test
    fun `an event that names a product twice is refused by request validation and writes nothing`() {
        val request = createPurchaseActivityRecordRequest(
            items = listOf(Item(productId = 1, quantity = 2), Item(productId = 1, quantity = 1)),
        )

        assertThat(violationsOf(request)).containsExactly("An order confirmed event must name each product once.")
        assertThat(storedRows()).isEmpty()
    }

    private fun violationsOf(request: PurchaseActivityRecordRequest): List<String> {
        return assertThrows<ConstraintViolationException> { purchaseActivityRecorder.record(request) }
            .constraintViolations.map { it.message }
    }

    /**
     * Every confirmation and activity in the database, each as its ID and values. Two reads are equal only when no row was
     * added, removed or changed between them.
     */
    private fun storedRows(): List<List<Any>> {
        val confirmations = storedConfirmations().map { listOf(it.id, it.eventId, it.orderId, it.confirmedAt) }
        val activities = storedActivities().map { listOf(it.id, it.orderId, it.productId, it.quantity, it.confirmedAt) }

        return confirmations + activities
    }

    private fun storedConfirmations(): List<ProcessedOrderConfirmation> {
        entityManager.flushAndClear()

        return entityManager
            .createQuery("select c from ProcessedOrderConfirmation c order by c.id", ProcessedOrderConfirmation::class.java)
            .resultList
    }

    private fun storedActivities(): List<PurchaseActivity> {
        entityManager.flushAndClear()

        return entityManager
            .createQuery("select a from PurchaseActivity a order by a.id", PurchaseActivity::class.java)
            .resultList
    }
}
