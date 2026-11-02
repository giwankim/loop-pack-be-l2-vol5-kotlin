package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.order.OrderPaymentSagaSteps
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.createPgTransaction
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.captureSql
import com.loopers.support.failTxBOnceAfterItsWrites
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessResourceFailureException
import org.wiremock.spring.InjectWireMock

private val log = KotlinLogging.logger {}

/**
 * A failure to leave the outbox row rolls the confirmation back, and a later reconcile confirms once through a lookup (ADR 0034
 * rule 2, order-outbox design 5). The assignment's `--tests '*OutboxAtomicityTest'` finds this class.
 *
 * The two scenes are controlled injections on the real MySQL with the test PG, not process kills: one throws where the row would
 * be saved, the other after TX B's writes. The row's ID is `IDENTITY`, so its `INSERT` goes out at `save`, not at a flush, and each
 * scene captures the statements it sent to show which side of that `INSERT` it failed on. A serialization failure takes the same
 * path as the scene before the insert, so it has no scene of its own. Everything but the statements is read in a new transaction,
 * so only what committed answers.
 */
@EnableTestPg
class OrderEventOutboxAtomicityTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderFinder: OrderFinder,
    private val paymentFinder: PaymentFinder,
    private val productFinder: ProductFinder,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"
        private const val TRANSACTION_KEY = "20261102:TR:194a7c"
        private const val INITIAL_STOCK = 5
        private const val QUANTITY = 2
        private const val RESERVED_STOCK = INITIAL_STOCK - QUANTITY
        private const val AMOUNT = 2_000L
    }

    /** Where the scene before the insert throws. Unstubbed, it is the real repository. */
    @MockkSpyBean
    private lateinit var orderEventOutboxRepository: OrderEventOutboxRepository

    /** Where the scene after the insert throws ([failTxBOnceAfterItsWrites]). Unstubbed, it is the real TX B. */
    @MockkSpyBean
    private lateinit var orderPaymentSagaSteps: OrderPaymentSagaSteps

    /** The test PG's server. Like [OrderPaymentIntegrationTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /** The confirm request of the scene. The test PG's create stub matches its card, and the fixture draws a new one each call. */
    private val request = createOrderConfirmRequest()

    @Test
    fun `a save that throws before the insert rolls the confirmation back, and a reconcile confirms once and leaves one row`() {
        prepareOrderTheCreateAnswersWithSuccess()
        val failure = DataAccessResourceFailureException("The database failed before the row's insert.")
        every { orderEventOutboxRepository.save(any()) } throws failure andThenAnswer { callOriginal() }

        val outboxInserts = confirmFailing()

        assertThat(outboxInserts).isEmpty()
        assertConfirmationRolledBack()
        val afterTheFailure = committedState()

        reconcileConfirmingOnce()

        logScene("before the insert", outboxInserts, afterTheFailure)
    }

    /**
     * The first TX B sends the row's insert at `save`, flushes the order and the payment and then throws, so the rollback takes
     * back a row MySQL had already received.
     */
    @Test
    fun `a TX B that fails after the insert takes the row back with the confirmation, and a reconcile confirms once`() {
        prepareOrderTheCreateAnswersWithSuccess()
        orderPaymentSagaSteps.failTxBOnceAfterItsWrites(entityManager)

        val outboxInserts = confirmFailing()

        assertThat(outboxInserts).hasSize(1)
        assertConfirmationRolledBack()
        val afterTheFailure = committedState()

        reconcileConfirmingOnce()

        logScene("after the insert", outboxInserts, afterTheFailure)
    }

    /** An order of [QUANTITY] from a stock of [INITIAL_STOCK], whose create the test PG accepts as a success. */
    private fun prepareOrderTheCreateAnswersWithSuccess() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = INITIAL_STOCK)), quantity = QUANTITY)
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = AMOUNT,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "SUCCESS",
        )
    }

    /** Confirms the order, expecting the injected failure, and returns the outbox inserts the call sent. */
    private fun confirmFailing(): List<String> {
        val captured = captureSql {
            assertThrows<DataAccessResourceFailureException> {
                orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)
            }
        }

        return captured.statements.filter { it.startsWith("insert into order_event_outbox") }
    }

    /** The PG holds the transaction it answered with, and TX A is all that committed: reserved stock, no transaction key, no row. */
    private fun assertConfirmationRolledBack() {
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(payment.transactionKey).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(RESERVED_STOCK)
            assertThat(orderEventOutboxRepository.count()).isZero()
        }
        assertThat(testPg.createCount()).isOne()
    }

    /**
     * The lookup finds the PG transaction of the first create, so no second create goes out. The stock stays where the reservation
     * put it: neither released to [INITIAL_STOCK] nor reduced a second time.
     */
    private fun reconcileConfirmingOnce() {
        val unknown = inNewTransaction { paymentFinder.findByOrder(order.id) }
        val transaction = createPgTransaction(unknown, transactionKey = TRANSACTION_KEY, status = PgStatus.SUCCESS)
        testPg.hasTransactions(unknown.pgOrderId, transaction)

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
            assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(RESERVED_STOCK)
            assertThat(orderEventOutboxRepository.count()).isOne()
            val row = orderEventOutboxRepository.findByEventId("order-confirmed:v1:${order.id}")!!
            assertThat(row.orderId).isEqualTo(order.id)
            assertThat(row.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        }
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** The scene's INFO line, after its assertions. A writing material copies one run's line (`scripts/scene-lines`). */
    private fun logScene(scene: String, outboxInserts: List<String>, afterTheFailure: String) {
        val afterTheReconcile = committedState()

        log.info {
            "Atomicity, $scene: outbox inserts sent ${outboxInserts.size}; after the failure: $afterTheFailure; " +
                "after the reconcile: $afterTheReconcile"
        }
    }

    /** What committed, read in a new transaction, and what the test PG received. */
    private fun committedState(): String {
        val committed = inNewTransaction {
            val payment = paymentFinder.findByOrder(order.id)
            "order ${order.id} ${orderFinder.find(user.id, order.id).status}, payment ${payment.status} " +
                "key ${payment.transactionKey}, stock ${productFinder.find(product.id).stock}, " +
                "rows ${orderEventOutboxRepository.count()}"
        }

        return "$committed, creates ${testPg.createCount()}, lookups ${testPg.lookupCount()}"
    }
}
