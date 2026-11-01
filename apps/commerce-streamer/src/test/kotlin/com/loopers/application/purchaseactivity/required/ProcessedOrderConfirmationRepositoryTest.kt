package com.loopers.application.purchaseactivity.required

import com.loopers.domain.purchaseactivity.createProcessedOrderConfirmation
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant

/**
 * Checks that the [ProcessedOrderConfirmationRepository] Spring Data builds keeps its contract on a real MySQL. The two unique
 * keys back recording's read when two consumers record the same event at once (ADR 0033 rule 3), so they are checked here: a
 * save that breaks each is refused under that key's name, and both are in `information_schema` under their names.
 *
 * The streamer has no JPA slice base, so this extends the service base and shares its cached context (its KDoc says why).
 */
class ProcessedOrderConfirmationRepositoryTest(
    private val processedOrderConfirmationRepository: ProcessedOrderConfirmationRepository,
) : BaseApplicationServiceTest() {
    /** A seventh fractional digit goes in, and the time reads back to the microsecond: the column keeps what the entity holds. */
    @Test
    fun `a saved confirmation reads back by its event id and by its order id after flush and clear`() {
        prepareProcessedOrderConfirmation(confirmedAt = Instant.parse("2026-10-27T01:02:03.123456789Z"))
        entityManager.flushAndClear()

        val byEventId = processedOrderConfirmationRepository.findByEventId(processedOrderConfirmation.eventId)
        val byOrderId = processedOrderConfirmationRepository.findByOrderId(processedOrderConfirmation.orderId)

        assertThat(byEventId).isNotNull().isNotSameAs(processedOrderConfirmation)
        assertThat(byEventId?.id).isEqualTo(processedOrderConfirmation.id)
        assertThat(byEventId?.orderId).isEqualTo(processedOrderConfirmation.orderId)
        assertThat(byEventId?.confirmedAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
        assertThat(byOrderId?.id).isEqualTo(processedOrderConfirmation.id)
    }

    @Test
    fun `the lookups are null for another event id and another order id`() {
        prepareProcessedOrderConfirmation(eventId = "order-confirmed:v1:1", orderId = 1)
        entityManager.flushAndClear()

        assertThat(processedOrderConfirmationRepository.findByEventId("order-confirmed:v1:2")).isNull()
        assertThat(processedOrderConfirmationRepository.findByOrderId(2)).isNull()
    }

    @Test
    fun `a deleted confirmation is found neither by its event id nor by its order id`() {
        prepareProcessedOrderConfirmation()
        deleteProcessedOrderConfirmation()
        entityManager.flushAndClear()

        assertThat(processedOrderConfirmationRepository.findByEventId(processedOrderConfirmation.eventId)).isNull()
        assertThat(processedOrderConfirmationRepository.findByOrderId(processedOrderConfirmation.orderId)).isNull()
    }

    /** The ID is IDENTITY, so a save is an INSERT at once, and the second one is refused without waiting for a flush. */
    @Test
    fun `saving a second confirmation of the same event violates the event id unique key`() {
        prepareProcessedOrderConfirmation(eventId = "order-confirmed:v1:1", orderId = 1)
        val sameEvent = createProcessedOrderConfirmation(eventId = "order-confirmed:v1:1", orderId = 2)

        val exception = assertThrows<DataIntegrityViolationException> { processedOrderConfirmationRepository.save(sameEvent) }

        assertThat(exception).rootCause().hasMessageContaining("UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID")
    }

    @Test
    fun `saving a confirmation of the same order under another event id violates the order id unique key`() {
        prepareProcessedOrderConfirmation(eventId = "order-confirmed:v1:1", orderId = 1)
        val sameOrder = createProcessedOrderConfirmation(eventId = "order-confirmed:v2:1", orderId = 1)

        val exception = assertThrows<DataIntegrityViolationException> { processedOrderConfirmationRepository.save(sameOrder) }

        assertThat(exception).rootCause().hasMessageContaining("UK_PROCESSED_ORDER_CONFIRMATION_ON_ORDER_ID")
    }

    /** Every constraint on the table is listed, so a foreign key would show: order IDs are values here (ADR 0033 rule 1). */
    @Test
    fun `the two unique keys exist in the database under their names and the table has no foreign key`() {
        val constraints = entityManager
            .createNativeQuery(
                "select concat(constraint_name, ':', constraint_type) from information_schema.table_constraints " +
                    "where table_schema = database() and table_name = 'processed_order_confirmation'",
            )
            .resultList
        val uniqueKeys = entityManager
            .createNativeQuery(
                "select concat(index_name, ':', column_name) from information_schema.statistics " +
                    "where table_schema = database() and table_name = 'processed_order_confirmation' and non_unique = 0 " +
                    "and index_name <> 'PRIMARY'",
            )
            .resultList

        assertThat(constraints).containsExactlyInAnyOrder(
            "PRIMARY:PRIMARY KEY",
            "UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID:UNIQUE",
            "UK_PROCESSED_ORDER_CONFIRMATION_ON_ORDER_ID:UNIQUE",
        )
        assertThat(uniqueKeys).containsExactlyInAnyOrder(
            "UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID:event_id",
            "UK_PROCESSED_ORDER_CONFIRMATION_ON_ORDER_ID:order_id",
        )
    }
}
