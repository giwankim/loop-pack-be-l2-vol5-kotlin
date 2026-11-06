package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.OrderEventOutboxModifyService
import com.loopers.application.order.OrderEventRelay
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.order.OrderStatus
import com.loopers.support.CommerceStreamerProcess
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.core.env.Environment
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
 * database and topic. The clock is pinned, so the confirmation time is a literal.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=${OutboxDeliveryE2ETest.TOPIC}"])
class OutboxDeliveryE2ETest(
    private val orderFinder: OrderFinder,
    private val orderEventOutboxModifyService: OrderEventOutboxModifyService,
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

        /** A `datetime(6)` column in `Instant`'s form. The columns hold UTC (`jpa.yml`'s `NORMALIZE_UTC`). */
        private const val STORED_UTC_TIME = "date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ')"
    }

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
        val activitiesBeforeTheRelay = storedActivities()
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
        val recordedLine = streamer.awaitRecordedLines(expected = 1) { rowsLine() }.single()
        val publishedOutbox = committedOrderEventOutbox()
        val activities = storedActivities()
        val confirmations = storedConfirmations()
        val eventId = "order-confirmed:v1:${order.id}"
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.attemptCount).isOne()
        assertThat(publishedRecord.key()).isEqualTo(order.id.toString())
        assertThat(recordedLine).isEqualTo(
            "Recorded the order confirmed event. eventId=$eventId, orderId=${order.id}, " +
                "partition=${publishedRecord.partition()}, offset=${publishedRecord.offset()}, result=RECORDED",
        )
        assertThat(confirmations).containsExactly("$eventId order ${order.id} confirmed_at $CONFIRMED_AT")
        assertThat(activities).containsExactly("order ${order.id} product ${product.id} quantity 2 confirmed_at $CONFIRMED_AT")

        log.info {
            "Delivery, after the commit before the relay: order ${order.id} ${confirmedOrder.status}; " +
                "row ${describe(waitingOutbox)}; records ${recordsBeforeTheRelay.size}; " +
                "purchase activities ${activitiesBeforeTheRelay.size}; " +
                "a recreated relay: row ${describe(publishedOutbox)}; record key ${publishedRecord.key()} " +
                "partition ${publishedRecord.partition()} offset ${publishedRecord.offset()}; " +
                "deliveries ${streamer.recordedLines().size} ($recordedLine); " +
                "processed_order_confirmation [${confirmations.joinToString("; ")}]; " +
                "purchase_activity [${activities.joinToString("; ")}]"
        }
    }

    /** Starts the streamer on the context's MySQL and broker, with the class's topic and group, and keeps it for [terminateTheStreamer]. */
    private fun startTheStreamer(): CommerceStreamerProcess {
        val settings = CommerceStreamerProcess.Settings(
            jdbcUrl = environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url"),
            username = environment.getRequiredProperty("datasource.mysql-jpa.main.username"),
            password = environment.getRequiredProperty("datasource.mysql-jpa.main.password"),
            bootstrapServers = kafkaProperties.bootstrapServers,
            topic = orderEventsProperties.topic,
            group = GROUP,
            partitions = orderEventsProperties.partitions,
        )

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

    /** The streamer's purchase activities as committed, one line each. */
    private fun storedActivities(): List<String> {
        return nativeStrings(
            "select concat('order ', order_id, ' product ', product_id, ' quantity ', quantity, " +
                "' confirmed_at ', $STORED_UTC_TIME) from purchase_activity order by id",
        )
    }

    /**
     * Each row as the one string [sql] selects, read in a new transaction. The streamer's tables have no entity in commerce-api, so
     * their rows are read this way, with times formatted by MySQL as stored: a JDBC read would shift them into the test JVM's zone.
     */
    private fun nativeStrings(sql: String): List<String> {
        return inNewTransaction { entityManager.createNativeQuery(sql).resultList.map { it.toString() } }
    }

    /** The rows a wait prints when it runs out: the outbox row and the streamer's two tables. */
    private fun rowsLine(): String {
        return "row ${describe(committedOrderEventOutbox())}; processed_order_confirmation ${storedConfirmations()}; " +
            "purchase_activity ${storedActivities()}"
    }

    private fun describe(outbox: OrderEventOutbox): String {
        return "${outbox.eventId} ${outbox.status} attempts ${outbox.attemptCount} published_at ${outbox.publishedAt}"
    }
}
