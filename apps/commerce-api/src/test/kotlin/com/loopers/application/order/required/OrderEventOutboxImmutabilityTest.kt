package com.loopers.application.order.required

import com.loopers.domain.order.OrderEventOutbox
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * The event columns of a stored row never change, because they are excluded from Hibernate's `UPDATE` (ADR 0034 rule 7). This
 * step has no state change that could carry them along, so the scene changes the fields of a managed entity by reflection and
 * flushes.
 *
 * A second transaction has to read the result, so the class leaves the base's rolled-back test transaction, as
 * `BaseCommittingApplicationServiceTest` does. Rollback can't clean up then, so each test deletes what it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderEventOutboxImmutabilityTest(
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
    private val transactionManager: PlatformTransactionManager,
) : BaseRepositoryTest() {
    companion object {
        private const val ORDER_ID = 12345L
        private const val EVENT_ID = "order-confirmed:v1:12345"
        private const val PAYLOAD = """{"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[]}"""

        /** Valid JSON, so that an `UPDATE` carrying it would reach the row rather than be refused by the check constraint. */
        private const val OTHER_PAYLOAD = """{"orderId":99999}"""
    }

    @AfterEach
    fun deleteTheCommittedRows() {
        inNewTransaction { entityManager.createNativeQuery("delete from order_event_outbox").executeUpdate() }
    }

    @Test
    fun `changing a managed row's event columns and flushing leaves the values read in a new transaction unchanged`() {
        inNewTransaction { prepareOrderEventOutbox(orderId = ORDER_ID, payload = PAYLOAD) }

        inNewTransaction {
            val managed = orderEventOutboxRepository.findByEventId(EVENT_ID)!!
            managed.setByReflection("eventId", "order-confirmed:v1:99999")
            managed.setByReflection("orderId", 99999L)
            managed.setByReflection("eventType", "OtherEvent")
            managed.setByReflection("schemaVersion", 2)
            managed.setByReflection("messageKey", "99999")
            managed.setByReflection("payload", OTHER_PAYLOAD)
            assertThat(managed.payload).isEqualTo(OTHER_PAYLOAD)
            entityManager.flush()
        }

        val reread = inNewTransaction { orderEventOutboxRepository.findByEventId(EVENT_ID)!! }

        assertThat(reread.eventId).isEqualTo(EVENT_ID)
        assertThat(reread.orderId).isEqualTo(ORDER_ID)
        assertThat(reread.eventType).isEqualTo("OrderConfirmed")
        assertThat(reread.schemaVersion).isEqualTo(1)
        assertThat(reread.messageKey).isEqualTo("12345")
        assertThat(reread.payload).isEqualTo(PAYLOAD)
    }

    private fun OrderEventOutbox.setByReflection(field: String, value: Any) {
        OrderEventOutbox::class.java.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
    }

    private fun <T> inNewTransaction(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return TransactionTemplate(transactionManager).execute { block() } as T
    }
}
