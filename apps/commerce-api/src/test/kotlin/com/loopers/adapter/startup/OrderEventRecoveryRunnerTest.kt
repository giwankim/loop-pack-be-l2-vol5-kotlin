package com.loopers.adapter.startup

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.provided.OrderEventRecoverer
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.linesAt
import com.loopers.support.restartedCommerceApi
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.context.TestPropertySource
import org.wiremock.spring.InjectWireMock
import java.time.Duration

/**
 * A person recovers orders by restarting the app with `order-events.recovery.order-ids` (ADR 0036 rule 2). Each scene leaves an
 * order confirmed without a row in this class's context, then starts the app again on the same containers, as that person would, and
 * reads what committed in a new transaction.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=$TOPIC"])
class OrderEventRecoveryRunnerTest(
    private val orderEventRecoverer: OrderEventRecoverer,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        /** The restarted relay ticks within a second. The rest is room for a slow machine. */
        private val PUBLISH_WAIT: Duration = Duration.ofSeconds(10)

        /** No order has this ID: the tables are emptied after every scene and IDs start at 1. */
        private const val NONEXISTENT_ORDER_ID = 999_999L
    }

    /** Where TX B's row goes missing ([confirmAnOrderLeavingNoRow]). Unstubbed, it is the real repository. */
    @MockkSpyBean
    private lateinit var orderEventOutboxRepository: OrderEventOutboxRepository

    /** The test PG's server. Like [com.loopers.application.order.provided.OrderEventRecovererTest], WireMock's listener injects it. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    private lateinit var orderEvents: TopicReader

    /** Logs from the application's order package, where recovery logs each order it ran for. */
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
    fun `with the property absent, startup recovers nothing and writes no row`() {
        val missingOrder = confirmAnOrderLeavingNoRow()

        started { }

        assertNoRowAndNoRecovery(missingOrder)
    }

    @Test
    fun `with the property empty, startup recovers nothing and writes no row`() {
        val missingOrder = confirmAnOrderLeavingNoRow()

        started("--order-events.recovery.order-ids=") { }

        assertNoRowAndNoRecovery(missingOrder)
    }

    /**
     * The two scenes above can't tell no call from a call with no IDs, which would add nothing and log nothing too. The restarted
     * context has no double of the port, so this one runs the runner by itself over an environment where the property is empty.
     */
    @Test
    fun `with the property empty, the runner doesn't call the port`() {
        val recoverer = mockk<OrderEventRecoverer>()
        val environment = MockEnvironment().withProperty("order-events.recovery.order-ids", "")

        OrderEventRecoveryRunner(recoverer, environment).run(DefaultApplicationArguments())

        verify(exactly = 0) { recoverer.recover(any()) }
    }

    @Test
    fun `with order IDs set, startup adds each order's row and the relay publishes it`() {
        val firstOrder = confirmAnOrderLeavingNoRow()
        val secondOrder = confirmAnOrderLeavingNoRow()

        started(
            "--order-events.recovery.order-ids=${firstOrder.id},${secondOrder.id}",
            "--order-events.relay.enabled=true",
        ) {
            await().atMost(PUBLISH_WAIT).until { isPublished(firstOrder) && isPublished(secondOrder) }
        }

        val publishedKeys = orderEvents.publishedRecords(expected = 2).map { it.key() }
        assertThat(publishedKeys).containsExactlyInAnyOrder(firstOrder.id.toString(), secondOrder.id.toString())
    }

    /** With the relay off, as under `test`, the added row waits pending for the instance that runs the relay. */
    @Test
    fun `with order IDs set and the relay off, startup adds the row and leaves it pending`() {
        val missingOrder = confirmAnOrderLeavingNoRow()

        started("--order-events.recovery.order-ids=${missingOrder.id}") { }

        assertThat(committedOrderEventOutbox(missingOrder.id).status).isEqualTo(OrderEventOutboxStatus.PENDING)
    }

    /**
     * The expected refusal is whatever the port throws for the order, so the scene holds however the port refuses. Spring wraps only a
     * runner's checked exception (in `Failed to execute ApplicationRunner`), so an unchecked refusal is what the start throws, after
     * Spring closed the context.
     */
    @Test
    fun `a refusal at startup fails the start with the refusal`() {
        val refusal = checkNotNull(runCatching { orderEventRecoverer.recover(listOf(NONEXISTENT_ORDER_ID)) }.exceptionOrNull())

        val exception = assertThrows<RuntimeException> {
            started("--order-events.recovery.order-ids=$NONEXISTENT_ORDER_ID") { }
        }

        assertThat(exception).isInstanceOf(refusal::class.java).hasMessage(refusal.message)
        assertThat(inNewTransaction { orderEventOutboxRepository.count() }).isZero()
    }

    /** The row of [order]'s event, if one committed, read in a new transaction. */
    private fun committedRowOrNull(order: Order): OrderEventOutbox? {
        return inNewTransaction { orderEventOutboxRepository.findByEventId("order-confirmed:v1:${order.id}") }
    }

    private fun isPublished(order: Order): Boolean {
        return committedOrderEventOutbox(order.id).status == OrderEventOutboxStatus.PUBLISHED
    }

    private fun assertNoRowAndNoRecovery(order: Order) {
        assertThat(committedRowOrNull(order)).isNull()
        assertThat(orderApplicationLogs.linesAt(Level.INFO).map { it.formattedMessage }).noneMatch { it.startsWith("Ran 누락 복구") }
    }

    /**
     * Confirms an order through the real TX A and TX B with TX B's row saved nowhere, as week 6 left it (ADR 0036 rule 5). The spy's
     * stub covers the first save only, so a later save is the real one.
     */
    private fun confirmAnOrderLeavingNoRow(): Order {
        val preparedOrder = prepareOrder(prepareProduct() to 1)
        every { orderEventOutboxRepository.save(any()) } answers { firstArg() } andThenAnswer { callOriginal() }
        confirmOrder(testPg, preparedOrder)

        assertThat(committedRowOrNull(preparedOrder)).isNull()

        return preparedOrder
    }

    /** The app restarted on the same containers and test PG ([restartedCommerceApi]) with [args]. This class's spy is not in it. */
    private fun started(vararg args: String, block: (ConfigurableApplicationContext) -> Unit) {
        restartedCommerceApi(server.baseUrl(), "--order-events.topic=$TOPIC", *args, block = block)
    }
}

private const val TOPIC = "order-events-recovery-runner"
