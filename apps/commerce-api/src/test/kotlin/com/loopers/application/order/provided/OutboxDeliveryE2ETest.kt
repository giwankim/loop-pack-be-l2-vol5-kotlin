package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.OrderEventOutboxModifyService
import com.loopers.application.order.OrderEventRelay
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.Product
import com.loopers.support.CommerceStreamerProcess
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.stopRelayAfterBrokerAckBeforeMarkingPublished
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.core.env.Environment
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.TestPropertySource
import org.wiremock.spring.InjectWireMock
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * An order from its confirmation to the purchase activity the real commerce-streamer records (order-outbox design 14.4 and 14.5).
 * The scenes confirm through the provided ports with the test PG, run the relay by hand, and read what each side left: rows from the
 * database, records from the broker, and deliveries from the streamer's `Recorded the order confirmed event` lines.
 *
 * The consumer is commerce-streamer's bootJar at HEAD, run as a child process against the same MySQL and broker
 * ([CommerceStreamerProcess]). A scene starts it, and [terminateTheStreamer] ends it before the base truncates the tables, so no
 * consumer is reading while they empty. The class names its own topic and group, so no other class's records reach this consumer.
 * The streamer's tables are its own and the base doesn't truncate them; `ddl-auto=create` recreates them at each start.
 *
 * Each stop is a controlled stop: the JVM stays up, no process is killed, and the restart is a new [OrderEventRelay] on the same
 * database and topic. The stop after the broker's ack is a one-shot stub on the spied [OrderEventOutboxModifyService], the same as
 * step 2's. The clock is pinned, so the confirmation time is a literal.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=${OutboxDeliveryE2ETest.TOPIC}"])
class OutboxDeliveryE2ETest(
    private val orderFinder: OrderFinder,
    private val paymentFinder: PaymentFinder,
    private val productFinder: ProductFinder,
    private val pointAccountFinder: PointAccountFinder,
    private val orderEventPublisher: OrderEventPublisher,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
    private val environment: Environment,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        const val TOPIC = "order-events-outbox-delivery"

        private const val GROUP = "commerce-streamer-outbox-delivery"

        /** Already in microseconds, so the order, the row and the streamer's tables keep it as it is. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-11-06T01:02:03.123456Z")
        private val ROUND_STARTED_AT: Instant = Instant.parse("2026-11-06T01:02:08.123456Z")

        /** A second past the lease the stopped round left (due again at [ROUND_STARTED_AT] plus 60 s), as in step 2's stop scene. */
        private val PAST_THE_LEASE: Instant = Instant.parse("2026-11-06T01:03:09.123456Z")

        private const val STOCK = 10
        private const val POINTS = 10_000L
    }

    /** Where the republish scene stops the relay (`stopRelayAfterBrokerAckBeforeMarkingPublished`). Unstubbed, it is the real service. */
    @MockkSpyBean
    private lateinit var orderEventOutboxModifyService: OrderEventOutboxModifyService

    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    private lateinit var orderEvents: TopicReader

    private var streamer: CommerceStreamerProcess? = null

    @BeforeEach
    fun readTheTopicFromItsEnd() {
        orderEvents = TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic)
    }

    /** Runs before the base's truncation, since JUnit runs a subclass's `@AfterEach` first, and runs when a scene failed too. */
    @AfterEach
    fun terminateTheStreamer() {
        streamer?.terminate()
        streamer = null
    }

    @AfterEach
    fun closeTheTopicReader() {
        orderEvents.close()
    }

    /**
     * The two §6 rows step 2 left at the broker (order-outbox design 13.1), carried to the consumer. After the confirmation commits
     * and before any relay runs, the order is confirmed, its row is pending and the consumer has nothing to record. A recreated relay
     * then publishes the row, and the streamer records the purchase activity once.
     */
    @Test
    fun `a confirmed order no relay has reached has no purchase activity, and a recreated relay's publish records it`() {
        prepareOrder(quantity = 2)
        val tablesBeforeTheStart = commerceTables()
        val streamer = startTheStreamer()

        val committedOrder = inNewTransaction { orderFinder.find(user.id, order.id) }
        assertThat(commerceTables()).isEqualTo(tablesBeforeTheStart)
        assertThat(committedOrder.status).isEqualTo(OrderStatus.DRAFT)

        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg)

        val confirmedOrder = inNewTransaction { orderFinder.find(user.id, order.id) }
        val waitingOutbox = committedOrderEventOutbox()
        val recordsBeforeTheRelay = orderEvents.publishedRecords(expected = 0)
        val activitiesBeforeTheRelay = storedPurchaseActivities()
        assertThat(confirmedOrder.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(waitingOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(waitingOutbox.attemptCount).isZero()
        assertThat(recordsBeforeTheRelay).isEmpty()
        assertThat(activitiesBeforeTheRelay).isEmpty()
        assertThat(storedConfirmations()).isEmpty()
        assertThat(streamer.recordedLines()).isEmpty()

        clock.pin(ROUND_STARTED_AT)
        newRelay().publishDueEvents()

        val recordsAfterTheRelay = orderEvents.publishedRecords(expected = 1)
        assertThat(recordsAfterTheRelay)
            .describedAs { "records on the broker; ${rowsLine()}\nThe streamer's recent log:\n${streamer.recentLog()}" }
            .hasSize(1)
        val publishedRecord = recordsAfterTheRelay.single()
        val deliveryLine = streamer.awaitRecordedLines(expected = 1) { rowsLine() }.single()
        val publishedOutbox = committedOrderEventOutbox()
        val activities = storedPurchaseActivities()
        val confirmations = storedConfirmations()
        val eventId = "order-confirmed:v1:${order.id}"
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.attemptCount).isOne()
        assertThat(publishedRecord.key()).isEqualTo(order.id.toString())
        assertThat(deliveryLine).isEqualTo(recordedLine(eventId, publishedRecord, result = "RECORDED"))
        assertThat(confirmations).containsExactly("$eventId order ${order.id} confirmed_at $CONFIRMED_AT")
        assertThat(activities).containsExactly("order ${order.id} product ${product.id} quantity 2 confirmed_at $CONFIRMED_AT")

        log.info {
            "Delivery, after the commit before the relay: order ${order.id} ${confirmedOrder.status}; " +
                "row ${describe(waitingOutbox)}; records ${recordsBeforeTheRelay.size}; " +
                "purchase activities ${activitiesBeforeTheRelay.size}; " +
                "a recreated relay: row ${describe(publishedOutbox)}; record key ${publishedRecord.key()} " +
                "partition ${publishedRecord.partition()} offset ${publishedRecord.offset()}; " +
                "deliveries ${streamer.recordedLines().size} ($deliveryLine); " +
                "processed_order_confirmation [${confirmations.joinToString("; ")}]; " +
                "purchase_activity [${activities.joinToString("; ")}]"
        }
    }

    /**
     * The assignment's §7 (order-outbox design 14.5): one order of two products, 2 and 1 of each, from its confirmation to the purchase
     * activity, through a republish. The first relay stops after the broker's ack and before `PUBLISHED` is saved, so the broker holds a
     * record while the row stays pending under its lease. A recreated relay past the lease publishes the same event again. The streamer
     * receives both records, records the first, and finds the second already recorded, so the purchase activity is left once. The order
     * side doesn't move after the confirmation: no PG call, no stock taken or given back, and no points spent.
     */
    @Test
    fun `an order republished after the broker's ack reaches the streamer twice and leaves its purchase activity once`() {
        val shirt = prepareProduct(stock = STOCK)
        val socks = prepareProduct(stock = STOCK)
        prepareOrder(shirt to 2, socks to 1)
        charge(POINTS)
        val streamer = startTheStreamer()
        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg)
        val stocksAfterTheConfirmation = stocksOf(shirt, socks)
        clock.pin(ROUND_STARTED_AT)
        orderEventOutboxModifyService.stopRelayAfterBrokerAckBeforeMarkingPublished()

        assertThrows<DataAccessResourceFailureException> { newRelay().publishDueEvents() }

        val firstRecord = orderEvents.publishedRecords(expected = 1).single()
        val stoppedOutbox = committedOrderEventOutbox()
        val firstLine = streamer.awaitRecordedLines(expected = 1) { rowsLine() }.single()
        assertThat(stoppedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(stoppedOutbox.attemptCount).isOne()
        assertThat(stoppedOutbox.publishedAt).isNull()

        clock.pin(PAST_THE_LEASE)
        newRelay().publishDueEvents()

        val secondRecord = orderEvents.publishedRecords(expected = 1).single()
        val recordedLines = streamer.awaitRecordedLines(expected = 2) { rowsLine() }
        val confirmedOrder = inNewTransaction { orderFinder.find(user.id, order.id) }
        val payment = inNewTransaction { paymentFinder.findByOrder(order.id) }
        val publishedOutbox = committedOrderEventOutbox()
        val confirmations = storedConfirmations()
        val activities = storedPurchaseActivities()
        val stocks = stocksOf(shirt, socks)
        val balance = inNewTransaction { pointAccountFinder.findByUser(user.id).balance.amount }
        val eventId = "order-confirmed:v1:${order.id}"
        assertThat(confirmedOrder.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(rowCount("orders")).isOne()
        assertThat(payment.transactionKey).isNotNull()
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.requestCount()).isOne()
        assertThat(rowCount("order_event_outbox")).isOne()
        assertThat(publishedOutbox.eventId).isEqualTo(eventId)
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.attemptCount).isEqualTo(2)
        assertThat(publishedOutbox.publishedAt).isEqualTo(PAST_THE_LEASE)
        assertThat(secondRecord.value()).isEqualTo(firstRecord.value())
        assertThat(firstRecord.value()).contains(""""eventId":"$eventId"""")
        assertThat(secondRecord.key()).isEqualTo(firstRecord.key())
        assertThat(secondRecord.partition()).isEqualTo(firstRecord.partition())
        assertThat(secondRecord.offset()).isGreaterThan(firstRecord.offset())
        assertThat(recordedLines).containsExactly(
            recordedLine(eventId, firstRecord, result = "RECORDED"),
            recordedLine(eventId, secondRecord, result = "ALREADY_RECORDED"),
        )
        assertThat(recordedLines.first()).isEqualTo(firstLine)
        assertThat(confirmations).containsExactly("$eventId order ${order.id} confirmed_at $CONFIRMED_AT")
        assertThat(activities).containsExactly(
            "order ${order.id} product ${shirt.id} quantity 2 confirmed_at $CONFIRMED_AT",
            "order ${order.id} product ${socks.id} quantity 1 confirmed_at $CONFIRMED_AT",
        )
        assertThat(stocksAfterTheConfirmation).containsExactly(STOCK - 2, STOCK - 1)
        assertThat(stocks).isEqualTo(stocksAfterTheConfirmation)
        assertThat(balance).isEqualTo(POINTS.toBigInteger())

        log.info {
            "Delivery, republished after the broker's ack: order ${order.id} ${confirmedOrder.status}, " +
                "PG requests ${testPg.requestCount()}; after the stop: row ${describe(stoppedOutbox)}, " +
                "record ${describe(firstRecord)}; past the lease: row ${describe(publishedOutbox)}, " +
                "record ${describe(secondRecord)}; deliveries ${recordedLines.size} (${recordedLines.joinToString("; ")}); " +
                "processed_order_confirmation [${confirmations.joinToString("; ")}]; " +
                "purchase_activity [${activities.joinToString("; ")}]; " +
                "stock $stocksAfterTheConfirmation after the confirmation, $stocks at the end, of $STOCK each; " +
                "points $balance of $POINTS"
        }
    }

    /** The line the streamer logs for one delivery of [record]. */
    private fun recordedLine(eventId: String, record: ConsumerRecord<String, String>, result: String): String {
        return "Recorded the order confirmed event. eventId=$eventId, orderId=${order.id}, " +
            "partition=${record.partition()}, offset=${record.offset()}, result=$result"
    }

    /** Each product's stock, as committed. */
    private fun stocksOf(vararg products: Product): List<Int> {
        return inNewTransaction { products.map { productFinder.find(it.id).stock } }
    }

    /** How many rows [table] holds, as committed. */
    private fun rowCount(table: String): Int {
        return nativeStrings("select count(*) from `$table`").single().toInt()
    }

    private fun describe(record: ConsumerRecord<String, String>): String {
        return "key ${record.key()} partition ${record.partition()} offset ${record.offset()}"
    }

    /** Starts the streamer on the context's MySQL and broker, with the class's topic and group, and keeps it for [terminateTheStreamer]. */
    private fun startTheStreamer(): CommerceStreamerProcess {
        val settings = CommerceStreamerProcess.Settings.of(environment, kafkaProperties, orderEventsProperties, GROUP)

        return CommerceStreamerProcess.start(settings).also { streamer = it }
    }

    /** What a restart makes: a new instance on the context's beans. */
    private fun newRelay(): OrderEventRelayer {
        return OrderEventRelay(orderEventOutboxModifyService, orderEventPublisher)
    }

    /**
     * Each commerce table's definition as `SHOW CREATE TABLE` gives it, so a start that dropped, recreated or altered one would differ.
     * The streamer's two tables are left out, since its start recreates them.
     */
    private fun commerceTables(): Map<String, String> {
        val tables = nativeStrings(
            "select table_name from information_schema.tables where table_schema = database() " +
                "and table_name not in ('processed_order_confirmation', 'purchase_activity') order by table_name",
        )

        return inNewTransaction {
            tables.associateWith { table ->
                val definition = entityManager.createNativeQuery("show create table `$table`").singleResult as Array<*>
                definition[1].toString()
            }
        }
    }

    /** The streamer's processed confirmations as committed, one line each. */
    private fun storedConfirmations(): List<String> {
        return nativeStrings(
            "select concat(event_id, ' order ', order_id, ' confirmed_at ', $STORED_UTC_TIME) " +
                "from processed_order_confirmation order by id",
        )
    }

    /** The rows a wait prints when it runs out: the outbox row and the streamer's two tables. */
    private fun rowsLine(): String {
        return "row ${describe(committedOrderEventOutbox())}; processed_order_confirmation ${storedConfirmations()}; " +
            "purchase_activity ${storedPurchaseActivities()}"
    }

    private fun describe(outbox: OrderEventOutbox): String {
        return "${outbox.eventId} ${outbox.status} attempts ${outbox.attemptCount} published_at ${outbox.publishedAt}"
    }
}
