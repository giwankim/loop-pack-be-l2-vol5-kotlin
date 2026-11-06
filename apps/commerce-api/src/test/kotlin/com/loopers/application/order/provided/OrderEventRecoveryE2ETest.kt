package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.Product
import com.loopers.support.CommerceStreamerProcess
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.restartedCommerceApi
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.core.env.Environment
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * The assignment's §5 (order-outbox design 14.3, ADR 0036 rule 5): week 6's two cases are reproduced in this run and recovered in the
 * same run. This is a reproduction, not a recovery of week 6's own incident: week 6's database is gone, and the week 6 code that runs
 * is today's TX B with a test hook in place of the one point where week 6 differed.
 *
 * In this class's context, a spy on [OrderEventOutboxRepository] makes TX B's `save` write no row and register an after-commit hook,
 * as week 6 published after the commit instead of leaving a row. A's hook sends nothing: A's stop is a send that never happened, not a
 * process kill. B's hook sends the row it was given through today's [OrderEventPublisher], whose record bytes are week 6's (design 10),
 * and the real commerce-streamer records it. So both orders are confirmed with no row, and only B has its purchase activity.
 *
 * The app then starts again on the same containers with `order-events.recovery.order-ids` set to A and B and the relay on, without
 * the spy ([restartedCommerceApi], `OrderPaymentIntegrationTest.restarted()`'s precedent). 누락 복구 adds both rows, the relay publishes them, the
 * streamer records A's purchase activity and finds B's already recorded. A third start with the same IDs and the relay on finds both rows
 * already present, and while it runs for [SECOND_START_HOLD] nothing changes. The containers are this JVM's Testcontainers, which the design takes as the assignment's new isolated
 * run. This class's own context stays up throughout, with its relay off as under `test`, so it publishes nothing.
 *
 * The scene is one `@Test`, so no cleanup runs between its steps, and no step deletes a row, inserts a `SUCCESS`, writes a `status`
 * or cleans a table before the end. Each step logs what it observed in one INFO line, for `scripts/scene-lines`.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=${OrderEventRecoveryE2ETest.TOPIC}"])
class OrderEventRecoveryE2ETest(
    private val orderFinder: OrderFinder,
    private val productFinder: ProductFinder,
    private val paymentFinder: PaymentFinder,
    private val orderEventPublisher: OrderEventPublisher,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
    private val environment: Environment,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        const val TOPIC = "order-events-recovery-e2e"

        private const val GROUP = "commerce-streamer-recovery-e2e"

        /** Already in microseconds, so the order, the row and the streamer's tables keep them as they are. */
        private val A_CONFIRMED_AT: Instant = Instant.parse("2026-10-29T01:02:03.123456Z")
        private val B_CONFIRMED_AT: Instant = Instant.parse("2026-10-29T01:02:04.654321Z")

        /** The restarted relay ticks within a second. The rest is room for a slow machine. */
        private val PUBLISH_WAIT: Duration = Duration.ofSeconds(10)

        /** How long the second start runs with its relay on: two of the relay's 1 s ticks, so a round that would send again has run. */
        private val SECOND_START_HOLD: Duration = Duration.ofSeconds(2)
    }

    /** Where TX B's row goes missing in this class's context ([confirmAsWeek6]). The restarted contexts have the real repository. */
    @MockkSpyBean
    private lateinit var orderEventOutboxRepository: OrderEventOutboxRepository

    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    private lateinit var orderEvents: TopicReader

    private var streamer: CommerceStreamerProcess? = null

    /** Logs from the application's order package, where 누락 복구 logs each order it ran for, in every context of this JVM. */
    private val orderApplicationLogs = ListAppender<ILoggingEvent>()

    private val orderApplicationLogger = LoggerFactory.getLogger("com.loopers.application.order") as Logger

    @BeforeEach
    fun readTheTopicFromItsEnd() {
        orderEvents = TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic)
    }

    @BeforeEach
    fun captureLogs() {
        orderApplicationLogs.start()
        orderApplicationLogger.addAppender(orderApplicationLogs)
    }

    /** Runs before the base's truncation, since JUnit runs a subclass's `@AfterEach` first, and runs when the scene failed too. */
    @AfterEach
    fun terminateTheStreamer() {
        streamer?.terminate()
        streamer = null
    }

    @AfterEach
    fun closeTheTopicReader() {
        orderEvents.close()
    }

    @AfterEach
    fun releaseLogs() {
        orderApplicationLogger.detachAppender(orderApplicationLogs)
        orderApplicationLogs.stop()
    }

    @Test
    fun `week 6's two cases, reproduced in this run, are recovered by a restart, and a second restart changes nothing`() {
        val productA = prepareProduct()
        val productB = prepareProduct()
        val orderA = prepareOrder(productA to 2)
        val orderB = prepareOrder(productB to 1)
        val stockBefore = stocks(productA, productB)
        val streamer = startTheStreamer()

        clock.pin(A_CONFIRMED_AT)
        confirmAsWeek6(orderA, sendsAfterTheCommit = false)
        clock.pin(B_CONFIRMED_AT)
        confirmAsWeek6(orderB, sendsAfterTheCommit = true)

        val week6Records = orderEvents.publishedRecords(expected = 1)
        val week6Lines = streamer.awaitRecordedLines(expected = 1) { rowsLine() }
        val confirmedA = committedOrder(orderA)
        val confirmedB = committedOrder(orderB)
        val reproducedState = recoveryState(orderA, orderB, productA, productB)
        val creates = testPg.createCount()
        val pgTransactions = testPg.createdTransactionKeys()
        val payments = listOf(orderA, orderB).map { inNewTransaction { paymentFinder.findByOrder(it.id) } }
        assertThat(confirmedA.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(confirmedB.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(stocks(productA, productB)).isEqualTo(listOf(stockBefore[0] - 2, stockBefore[1] - 1))
        assertThat(storedRows()).isEmpty()
        assertThat(week6Records.map { it.key() }).containsExactly(orderB.id.toString())
        assertThat(week6Lines.single()).startsWith(
            "Recorded the order confirmed event. eventId=${eventIdOf(orderB)}, orderId=${orderB.id}, ",
        ).endsWith("result=RECORDED")
        assertThat(storedPurchaseActivities()).containsExactly(
            "order ${orderB.id} product ${productB.id} quantity 1 confirmed_at $B_CONFIRMED_AT",
        )
        assertThat(creates).isEqualTo(2)
        assertThat(payments.map { testPg.createCount(it.pgOrderId) }).containsExactly(1, 1)
        assertThat(pgTransactions).containsExactlyInAnyOrderElementsOf(payments.map { it.transactionKey })
        assertThat(testPg.lookupCount()).isZero()

        log.info {
            "W6 reproduction, before 누락 복구: A ${describeConfirmed(orderA)}, sent nothing after the commit; " +
                "B ${describeConfirmed(orderB)}, sent after the commit (record key ${week6Records.single().key()} " +
                "partition ${week6Records.single().partition()} offset ${week6Records.single().offset()}); " +
                "PG creates C=$creates, PG transactions T=${pgTransactions.size}, PG lookups ${testPg.lookupCount()}; " +
                "stock ${stockBefore.joinToString("/")} -> ${stocks(productA, productB).joinToString("/")}; " +
                "outbox rows ${storedRows().size}; deliveries ${week6Lines.size} (${week6Lines.single()}); " +
                "purchase_activity [${storedPurchaseActivities().joinToString("; ")}]"
        }

        val recoveryArgs = arrayOf(
            "--order-events.topic=$TOPIC",
            "--order-events.recovery.order-ids=${orderA.id},${orderB.id}",
            "--order-events.relay.enabled=true",
        )
        val firstStartLines = recoveryLinesDuring {
            restartedCommerceApi(server.baseUrl(), *recoveryArgs) {
                await().atMost(PUBLISH_WAIT).until { storedRows().count { it.status == OrderEventOutboxStatus.PUBLISHED } == 2 }
            }
        }

        val recoveredRecords = orderEvents.publishedRecords(expected = 2)
        val recoveredLines = streamer.awaitRecordedLines(expected = 3) { rowsLine() }.drop(1)
        val rowsAfterRecovery = storedRows()
        val activitiesAfterRecovery = storedPurchaseActivities()
        assertThat(firstStartLines).containsExactly(
            "Ran 누락 복구 for the order confirmed event. orderId=${orderA.id}, eventId=${eventIdOf(orderA)}, result=ADDED",
            "Ran 누락 복구 for the order confirmed event. orderId=${orderB.id}, eventId=${eventIdOf(orderB)}, result=ADDED",
        )
        assertThat(rowsAfterRecovery.map { it.eventId }).containsExactly(eventIdOf(orderA), eventIdOf(orderB))
        assertThat(recoveredRecords.map { it.key() }).containsExactlyInAnyOrder(orderA.id.toString(), orderB.id.toString())
        assertThat(recoveredRecords.single { it.key() == orderB.id.toString() }.value()).isEqualTo(week6Records.single().value())
        assertThat(recoveredLines).anySatisfy { line ->
            assertThat(line).startsWith("Recorded the order confirmed event. eventId=${eventIdOf(orderA)}, ")
                .endsWith("result=RECORDED")
        }.anySatisfy { line ->
            assertThat(line).startsWith("Recorded the order confirmed event. eventId=${eventIdOf(orderB)}, ")
                .endsWith("result=ALREADY_RECORDED")
        }
        assertThat(activitiesAfterRecovery).containsExactly(
            "order ${orderB.id} product ${productB.id} quantity 1 confirmed_at $B_CONFIRMED_AT",
            "order ${orderA.id} product ${productA.id} quantity 2 confirmed_at $A_CONFIRMED_AT",
        )
        assertThat(recoveryState(orderA, orderB, productA, productB)).isEqualTo(reproducedState)
        assertThat(testPg.createCount()).isEqualTo(creates)
        assertThat(payments.map { testPg.createCount(it.pgOrderId) }).containsExactly(1, 1)
        assertThat(testPg.createdTransactionKeys()).containsExactlyInAnyOrderElementsOf(pgTransactions)
        assertThat(testPg.lookupCount()).isZero()
        assertThat(testPg.unmatchedRequests()).isEmpty()

        log.info {
            "누락 복구, the first start with A and B: ${firstStartLines.joinToString("; ")}; " +
                "rows [${rowsAfterRecovery.joinToString("; ") { describe(it) }}]; " +
                "records [${recoveredRecords.joinToString("; ") { describe(it) }}]; " +
                "deliveries [${recoveredLines.joinToString("; ")}]; " +
                "purchase_activity [${activitiesAfterRecovery.joinToString("; ")}]; " +
                "PG creates ${testPg.createCount()}, PG transactions ${testPg.createdTransactionKeys().size}, " +
                "PG lookups ${testPg.lookupCount()}; orders, payments and stock unchanged: $reproducedState"
        }

        val requestsBeforeTheSecondStart = testPg.requestCount()
        val secondStartLines = recoveryLinesDuring {
            restartedCommerceApi(server.baseUrl(), *recoveryArgs) {
                await().during(SECOND_START_HOLD).atMost(SECOND_START_HOLD.plus(PUBLISH_WAIT)).until {
                    storedRows().map { describeWhole(it) } == rowsAfterRecovery.map { describeWhole(it) }
                }
            }
        }

        val recordsAfterTheSecondStart = orderEvents.publishedRecords(expected = 0)
        assertThat(secondStartLines).containsExactly(
            "Ran 누락 복구 for the order confirmed event. orderId=${orderA.id}, eventId=${eventIdOf(orderA)}, result=ALREADY_PRESENT",
            "Ran 누락 복구 for the order confirmed event. orderId=${orderB.id}, eventId=${eventIdOf(orderB)}, result=ALREADY_PRESENT",
        )
        assertThat(storedRows().map { describeWhole(it) }).isEqualTo(rowsAfterRecovery.map { describeWhole(it) })
        assertThat(recordsAfterTheSecondStart).isEmpty()
        assertThat(streamer.recordedLines()).hasSize(3)
        assertThat(storedPurchaseActivities()).isEqualTo(activitiesAfterRecovery)
        assertThat(recoveryState(orderA, orderB, productA, productB)).isEqualTo(reproducedState)
        assertThat(testPg.requestCount()).isEqualTo(requestsBeforeTheSecondStart)

        log.info {
            "누락 복구, the second start with the same IDs: ${secondStartLines.joinToString("; ")}; " +
                "rows ${storedRows().size}, unchanged while it ran ${SECOND_START_HOLD.toSeconds()} s with the relay on; " +
                "records ${recordsAfterTheSecondStart.size}; " +
                "deliveries ${streamer.recordedLines().size}; purchase_activity ${storedPurchaseActivities().size}, unchanged; " +
                "PG requests ${testPg.requestCount() - requestsBeforeTheSecondStart} more"
        }
    }

    /**
     * Confirms [order] through the real TX A, PG call and TX B, with TX B's `save` stubbed as week 6 had it: no row, and a hook after
     * the commit. With [sendsAfterTheCommit], the hook sends the row it was given and waits for the broker. Without it, the hook sends
     * nothing. Each call replaces the stub the call before it set.
     */
    private fun confirmAsWeek6(order: Order, sendsAfterTheCommit: Boolean) {
        every { orderEventOutboxRepository.save(any()) } answers {
            val row = firstArg<OrderEventOutbox>()
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        if (sendsAfterTheCommit) {
                            val result = orderEventPublisher.publish(listOf(row)).single()
                            check(result.failureMessage == null) {
                                "Week 6's send of ${row.eventId} failed: ${result.failureMessage}"
                            }
                        }
                    }
                },
            )
            row
        }

        confirmOrder(testPg, order)
    }

    /** Starts the streamer on the context's MySQL and broker, with the class's topic and group, and keeps it for [terminateTheStreamer]. */
    private fun startTheStreamer(): CommerceStreamerProcess {
        val settings = CommerceStreamerProcess.Settings.of(environment, kafkaProperties, orderEventsProperties, GROUP)

        return CommerceStreamerProcess.start(settings).also { streamer = it }
    }

    /** The `Ran 누락 복구` INFO lines logged while [block] ran. */
    private fun recoveryLinesDuring(block: () -> Unit): List<String> {
        val before = orderApplicationLogs.list.size
        block()

        return orderApplicationLogs.list.drop(before)
            .filter { it.level == Level.INFO }
            .map { it.formattedMessage }
            .filter { it.startsWith("Ran 누락 복구") }
    }

    /**
     * What 누락 복구 must not change, read in a new transaction: each order's status, confirmation time and last update, each payment's
     * PG order ID, state, transaction key and last update, and the products' stock. A reconfirmation, a consumed reservation or a
     * callback's reconcile would change one of them.
     */
    private fun recoveryState(orderA: Order, orderB: Order, productA: Product, productB: Product): String {
        val orders = listOf(orderA, orderB).joinToString("; ") { order ->
            val committed = committedOrder(order)
            val payment = inNewTransaction { paymentFinder.findByOrder(order.id) }
            "order ${committed.id} ${committed.status} confirmed_at ${committed.confirmedAt} updated_at ${committed.updatedAt} " +
                "payment ${payment.pgOrderId} ${payment.status} ${payment.transactionKey} updated_at ${payment.updatedAt}"
        }

        return "$orders; stock ${stocks(productA, productB).joinToString("/")}"
    }

    /**
     * The order's event ID, confirmation time, items and PG transaction identifiers, read in a new transaction, for the run's log.
     * The items are read inside that transaction.
     */
    private fun describeConfirmed(order: Order): String {
        return inNewTransaction {
            val committed = orderFinder.find(order.userId, order.id)
            val payment = paymentFinder.findByOrder(order.id)
            val items = committed.lineItems.joinToString(", ") { "product ${it.productId} quantity ${it.quantity}" }
            "order ${committed.id} ${committed.status} event ${eventIdOf(committed)} confirmed_at ${committed.confirmedAt} " +
                "items [$items] pg_order_id ${payment.pgOrderId} transaction_key ${payment.transactionKey}"
        }
    }

    private fun committedOrder(order: Order): Order {
        return inNewTransaction { orderFinder.find(order.userId, order.id) }
    }

    private fun stocks(vararg products: Product): List<Int> {
        return inNewTransaction { products.map { productFinder.find(it.id).stock } }
    }

    private fun eventIdOf(order: Order): String {
        return "order-confirmed:v1:${order.id}"
    }

    /** Every outbox row, read in a new transaction from the real repository behind the spy, in ID order. */
    private fun storedRows(): List<OrderEventOutbox> {
        return inNewTransaction {
            entityManager.createQuery("select o from OrderEventOutbox o order by o.id", OrderEventOutbox::class.java).resultList
        }
    }

    /** The rows a wait prints when it runs out: the outbox rows and the purchase activities. */
    private fun rowsLine(): String {
        return "rows [${storedRows().joinToString("; ") { describe(it) }}]; purchase_activity ${storedPurchaseActivities()}"
    }

    private fun describe(outbox: OrderEventOutbox): String {
        return "${outbox.eventId} ${outbox.status} attempts ${outbox.attemptCount} published_at ${outbox.publishedAt}"
    }

    /** Every column of the row, so a start that wrote any of them would differ. */
    private fun describeWhole(outbox: OrderEventOutbox): String {
        return "id ${outbox.id} ${outbox.eventId} order ${outbox.orderId} ${outbox.eventType} v${outbox.schemaVersion} " +
            "key ${outbox.messageKey} payload ${outbox.payload} ${outbox.status} attempts ${outbox.attemptCount} " +
            "next_attempt_at ${outbox.nextAttemptAt} published_at ${outbox.publishedAt} last_error ${outbox.lastError} " +
            "created_at ${outbox.createdAt} updated_at ${outbox.updatedAt}"
    }

    private fun describe(record: ConsumerRecord<String, String>): String {
        return "key ${record.key()} partition ${record.partition()} offset ${record.offset()}"
    }
}
