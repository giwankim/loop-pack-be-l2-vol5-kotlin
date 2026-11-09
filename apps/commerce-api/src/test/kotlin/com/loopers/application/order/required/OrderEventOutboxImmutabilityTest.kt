package com.loopers.application.order.required

import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * The event columns of a stored row never change, because they are excluded from Hibernate's `UPDATE` (ADR 0034 rule 7). Nothing in the
 * entity can change them, so the scenes change the fields of a managed entity by reflection and flush, on their own and together with
 * the relay's state changes (order-outbox design 10). The state change is what sends a real `UPDATE`, and without the tampering the
 * columns would be rewritten with the values they already hold, which would show nothing.
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
            managed.overwriteEventColumns()
            assertThat(managed.payload).isEqualTo(OTHER_PAYLOAD)
            entityManager.flush()
        }

        val reread = inNewTransaction { orderEventOutboxRepository.findByEventId(EVENT_ID)!! }

        assertEventColumnsUnchanged(reread)
    }

    @Test
    fun `the event columns read in a new transaction are unchanged after the row's state changes`() {
        inNewTransaction { prepareOrderEventOutbox(orderId = ORDER_ID, payload = PAYLOAD) }

        inNewTransaction {
            val managed = orderEventOutboxRepository.findByEventId(EVENT_ID)!!
            managed.overwriteEventColumns()
            managed.startPublishAttempt(Instant.parse("2026-10-27T01:02:03.123456Z"))
            orderEventOutboxRepository.save(managed)
        }
        inNewTransaction {
            val managed = orderEventOutboxRepository.findByEventId(EVENT_ID)!!
            managed.overwriteEventColumns()
            managed.markFailed("broker unavailable", Instant.parse("2026-10-27T01:02:03.623456Z"))
            orderEventOutboxRepository.save(managed)
        }
        inNewTransaction {
            val managed = orderEventOutboxRepository.findByEventId(EVENT_ID)!!
            managed.overwriteEventColumns()
            managed.markPublished(Instant.parse("2026-10-27T01:02:04.123456Z"))
            orderEventOutboxRepository.save(managed)
        }

        val reread = inNewTransaction { orderEventOutboxRepository.findByEventId(EVENT_ID)!! }

        assertThat(reread.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(reread.attemptCount).isOne()
        assertThat(reread.lastError).isEqualTo("broker unavailable")
        assertThat(reread.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:02:04.123456Z"))
        assertEventColumnsUnchanged(reread)
    }

    private fun assertEventColumnsUnchanged(reread: OrderEventOutbox) {
        assertThat(reread.eventId).isEqualTo(EVENT_ID)
        assertThat(reread.orderId).isEqualTo(ORDER_ID)
        assertThat(reread.eventType).isEqualTo("OrderConfirmed")
        assertThat(reread.schemaVersion).isEqualTo(1)
        assertThat(reread.messageKey).isEqualTo("12345")
        assertThat(reread.payload).isEqualTo(PAYLOAD)
    }

    private fun OrderEventOutbox.overwriteEventColumns() {
        setByReflection("eventId", "order-confirmed:v1:99999")
        setByReflection("orderId", 99999L)
        setByReflection("eventType", "OtherEvent")
        setByReflection("schemaVersion", 2)
        setByReflection("messageKey", "99999")
        setByReflection("payload", OTHER_PAYLOAD)
    }

    private fun OrderEventOutbox.setByReflection(field: String, value: Any) {
        OrderEventOutbox::class.java.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
    }

    private fun <T> inNewTransaction(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return TransactionTemplate(transactionManager).execute { block() } as T
    }
}
