package com.loopers.application.order.required

import com.loopers.domain.order.OrderEventOutbox
import com.loopers.support.Pause
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Two claims open at once, each in its own transaction on its own connection, take different rows (ADR 0035 rule 2). This scene is what
 * guards the index: without `IDX_ORDER_EVENT_OUTBOX_ON_STATUS_AND_NEXT_ATTEMPT_AT_AND_ID` the first claim locks every row it scans, and
 * the second comes back empty rather than slow (rule 10). There is deliberately no `EXPLAIN` assertion. It shows the database's
 * coordination, and doesn't make two relays a supported setup (rule 13).
 *
 * The backlog is above the size at which MySQL was measured using the index for this statement: a table scan at 150 rows, the index from
 * 200, and 500 rows after smaller tables too. At 45 rows that scan's filesort left the second claim empty with the index in place, so a
 * scene that size wouldn't be about the index (the relay probe note's plan by table size). The choice remains the optimizer's.
 *
 * The rows have to be committed for another connection to see them, so the class leaves the base's rolled-back test transaction, as
 * [OrderEventOutboxImmutabilityTest] does, and each test deletes what it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderEventOutboxClaimTest(
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
    private val transactionManager: PlatformTransactionManager,
) : BaseRepositoryTest() {
    companion object {
        private val DUE_AT: Instant = Instant.parse("2026-10-27T01:02:03Z")
        private val CLAIMED_AT: Instant = Instant.parse("2026-10-27T01:02:08Z")
        private const val AWAIT_LIMIT_SECONDS = 30L
    }

    @AfterEach
    fun deleteTheCommittedRows() {
        inNewTransaction { entityManager.createNativeQuery("delete from order_event_outbox").executeUpdate() }
    }

    @Test
    fun `with one claim held open in another thread, a second claim receives the next rows with no overlap`() {
        val orderIds = (1L..500L).toList()
        inNewTransaction { orderIds.forEach { prepareOrderEventOutbox(orderId = it, nextAttemptAt = DUE_AT) } }
        val firstClaimHeld = Pause()

        val firstClaim = CompletableFuture.supplyAsync {
            inNewTransaction {
                val firstClaimedOrderIds = claimDueOutboxes().map { it.orderId }
                firstClaimHeld.hold()
                firstClaimedOrderIds
            }
        }
        firstClaimHeld.awaitHeld()
        val secondClaimedOrderIds = try {
            inNewTransaction { claimDueOutboxes().map { it.orderId } }
        } finally {
            firstClaimHeld.release()
        }
        val firstClaimedOrderIds = firstClaim.get(AWAIT_LIMIT_SECONDS, TimeUnit.SECONDS)

        assertThat(firstClaimedOrderIds).isEqualTo(orderIds.subList(0, 20))
        assertThat(secondClaimedOrderIds).isEqualTo(orderIds.subList(20, 40))
    }

    private fun claimDueOutboxes(): List<OrderEventOutbox> {
        return orderEventOutboxRepository.findDueForUpdate(CLAIMED_AT, PageRequest.of(0, 20))
    }

    private fun <T> inNewTransaction(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return TransactionTemplate(transactionManager).execute { block() } as T
    }
}
