package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.required.OrderConfirmedEvent
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.product.Product
import com.loopers.support.EnableTestPg
import com.loopers.support.Pause
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.awaitLinesAt
import com.loopers.support.linesAt
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkBean
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import org.apache.kafka.common.errors.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.TestPropertySource
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * The publish gap of order-events design 6: the confirmation commits, and the relay's publish then fails or has not happened
 * yet, so the order stays confirmed with its stock consumed while the broker holds nothing (ADR 0032 rules 5 and 7). The
 * database and the broker are not one transaction. This is the unpublished case handed to week 7's outbox.
 *
 * The scenes confirm through the real `OrderConfirmer`, the test PG answering success and the real TX B, and then read the
 * order, the payment and the stock in a fresh transaction and the class's topic on the real broker. Only the template the
 * relay publishes with is replaced, by a mock whose `send` throws, or blocks on a [Pause] until the scene has read the
 * database and the broker. That stands in for a broker that cannot be reached: the producer's `send` blocks while it waits
 * for the topic's metadata, and throws once `max.block.ms` (60 s by default) has passed (design 5). The scene does not wait
 * that long. It is a failure injected into one call, not a process that was killed.
 *
 * Each scene pins the clock at [CONFIRMED_AT] and confirms the same order ([prepareTheOrder]). It logs what it observed: the
 * order ID, the PG order ID, the transaction key, the event that was not published, and the order, payment, stock and broker
 * state. Run it with
 * `./gradlew :apps:commerce-api:test --tests 'com.loopers.application.order.provided.OrderEventPublishGapTest'`.
 * `docs/research/week6-order-events-writing-material.md` keeps one run's values and the commit they came from.
 *
 * The mock lives only in this class's context. The class names a topic of its own, so a record another class's relay
 * publishes late cannot pass for one of this class's orders, whose IDs restart at 1 like theirs.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=order-events-publish-gap"])
class OrderEventPublishGapTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderFinder: OrderFinder,
    private val paymentFinder: PaymentFinder,
    private val productFinder: ProductFinder,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"
        private const val TRANSACTION_KEY = "20261027:TR:9e3b71"

        /** Where every scene pins the clock. Already in microseconds, so the order keeps it as it is. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")

        /** How long a scene waits for the relay's WARN line, which the relay logs on its own thread. */
        private val RELAY_WAIT: Duration = Duration.ofSeconds(10)

        /**
         * How long a scene watches for an ERROR after the relay's WARN line. An exception that left the relay would be logged
         * right after the line, once it reached the end of the relay and Spring's async exception handler.
         */
        private val QUIET_WAIT: Duration = Duration.ofSeconds(1)
    }

    /**
     * The template the relay publishes with. A scene stubs its `send`. It is no AOP proxy, so the stub goes on it directly.
     *
     * Spring still calls the template's lifecycle callbacks on the mock that holds its bean name, `afterSingletonsInstantiated`
     * first, while the context starts. Those return nothing, so functions returning `Unit` are relaxed. Every other call has to
     * be stubbed, so a `send` a scene did not stub fails instead of answering a default.
     */
    @MockkBean(relaxUnitFun = true)
    private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

    /** The test PG's server. Like [OrderPaymentIntegrationTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /** Where the relay's `send` stops in the scene that holds the publish. */
    private val pause = Pause()

    /** Reads the class's topic from where it ended before the scene. */
    private lateinit var orderEvents: TopicReader

    /** The product the order holds two of. */
    private lateinit var cheaper: Product

    /** The product the order holds one of. */
    private lateinit var pricier: Product

    /**
     * Logs from the Kafka adapter, where the relay logs a failed publish, and from Spring's default async exception handler,
     * which would log an exception that left the relay. The relay logs on its own thread, so the list is read under the
     * appender's lock ([linesAt]).
     */
    private val relayLogs = ListAppender<ILoggingEvent>()

    private val relayLoggers = listOf(
        LoggerFactory.getLogger("com.loopers.adapter.integration.kafka") as Logger,
        LoggerFactory.getLogger(SimpleAsyncUncaughtExceptionHandler::class.java) as Logger,
    )

    @BeforeEach
    fun readTheTopicFromItsEnd() {
        orderEvents = TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic)
    }

    @BeforeEach
    fun captureRelayLogs() {
        relayLogs.start()
        relayLoggers.forEach { it.addAppender(relayLogs) }
    }

    @AfterEach
    fun closeTheTopicReader() {
        orderEvents.close()
    }

    @AfterEach
    fun releaseRelayLogs() {
        relayLoggers.forEach { it.detachAppender(relayLogs) }
    }

    /**
     * The publish fails after TX B committed. The confirmation answers as in week 5, the order stays confirmed with its stock
     * consumed and its payment succeeded, the PG was asked to create once, and the broker has no record. The failure shows only
     * as one WARN line, and nothing leaves the relay.
     */
    @Test
    fun `a publish that fails after the confirmation committed leaves the order confirmed and no record on the broker`() {
        val request = prepareTheOrder()
        every { kafkaTemplate.send(any<String>(), any(), any()) } throws sendFailure()
        clock.pin(CONFIRMED_AT)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertTheConfirmationCommitted(confirmed)
        val records = orderEvents.publishedRecords(expected = 0)
        assertThat(records).isEmpty()
        assertThat(relayLogs.awaitLinesAt(Level.WARN, RELAY_WAIT).map { it.formattedMessage }).containsExactly(
            "Could not publish the order confirmed event. eventId=order-confirmed:v1:${order.id}, orderId=${order.id}, " +
                "cause=org.springframework.kafka.KafkaException: Send failed",
        )
        assertNothingLeftTheRelay()
        logTheUnpublishedCase("the publish failed", recordsOnTheTopic = records.size)
    }

    /**
     * The publish has not happened yet: the relay's `send` is stopped after TX B committed and the confirmation answered. Until
     * it goes on, the committed confirmation is readable and the broker has no record, so a process that stopped here would leave
     * the order confirmed and the event unpublished. The relay has logged nothing yet, which shows it is still stopped. Released,
     * the `send` fails as it would once `max.block.ms` passed. The scene waits for the relay's WARN line so the relay has
     * finished before the next scene, and asserts nothing about what becomes of the event: the gap is what it shows.
     */
    @Test
    fun `while the publish is stopped after the confirmation committed, the order is confirmed and the broker has no record`() {
        val request = prepareTheOrder()
        every { kafkaTemplate.send(any<String>(), any(), any()) } answers {
            pause.hold()
            throw sendFailure()
        }
        clock.pin(CONFIRMED_AT)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)
        pause.awaitHeld()

        assertTheConfirmationCommitted(confirmed)
        val records = orderEvents.publishedRecords(expected = 0)
        assertThat(records).isEmpty()
        assertThat(relayLogs.linesAt(Level.WARN)).isEmpty()
        logTheUnpublishedCase("the publish is stopped", recordsOnTheTopic = records.size)
        pause.release()
        assertThat(relayLogs.awaitLinesAt(Level.WARN, RELAY_WAIT)).hasSize(1)
        assertNothingLeftTheRelay()
    }

    /**
     * The order every scene confirms: two units of a product priced 1,000 and one of a product priced 2,000, each with stock 5,
     * listed in descending price. The test PG accepts its 4,000 create as a success under [TRANSACTION_KEY]. Returns the
     * request that confirms it.
     */
    private fun prepareTheOrder(): OrderConfirmRequest {
        cheaper = prepareProduct(price = 1_000, stock = 5)
        pricier = prepareProduct(price = 2_000, stock = 5)
        prepareOrder(pricier to 1, cheaper to 2)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = 4_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "SUCCESS",
        )

        return request
    }

    /**
     * [confirmed] is the week 5 answer, and in a fresh transaction the order is confirmed at the pinned instant, its payment
     * succeeded under the PG's transaction key, and each product keeps the stock the reservation took: 5 less the quantity
     * ordered, not released. The PG was asked to create once.
     */
    private fun assertTheConfirmationCommitted(confirmed: OrderInfo) {
        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(confirmed.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        inNewTransaction {
            val confirmedOrder = orderFinder.find(user.id, order.id)
            assertThat(confirmedOrder.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(confirmedOrder.confirmedAt).isEqualTo(CONFIRMED_AT)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
            assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(cheaper.id).stock).isEqualTo(3)
            assertThat(productFinder.find(pricier.id).stock).isEqualTo(4)
        }
        assertThat(testPg.createCount()).isOne()
    }

    /**
     * What `KafkaTemplate.send` throws when the producer gave up waiting for the topic's metadata: the broker could not be
     * reached within `max.block.ms`.
     */
    private fun sendFailure(): KafkaException {
        val cause = TimeoutException("Topic ${orderEventsProperties.topic} not present in metadata after 60000 ms.")

        return KafkaException("Send failed", cause)
    }

    /**
     * No ERROR for [QUIET_WAIT] after the relay's WARN line: Spring's async exception handler logged nothing, so no exception
     * left the relay.
     */
    private fun assertNothingLeftTheRelay() {
        await("no exception left the relay")
            .during(QUIET_WAIT)
            .atMost(QUIET_WAIT.multipliedBy(2))
            .until { relayLogs.linesAt(Level.ERROR).isEmpty() }
    }

    /**
     * Logs the unpublished case as week 7 starts from it, read in a fresh transaction: the order, its PG order ID and
     * transaction key, the event that was never published, and the state the order, the payment, the stock and the broker
     * were left in. The card is left out.
     */
    private fun logTheUnpublishedCase(scene: String, recordsOnTheTopic: Int) {
        inNewTransaction {
            val confirmedOrder = orderFinder.find(user.id, order.id)
            val payment = paymentFinder.findByOrder(order.id)
            val event = OrderConfirmedEvent.from(confirmedOrder)
            val stock = listOf(cheaper, pricier).associate { it.id to productFinder.find(it.id).stock }

            log.info {
                """
                |W7 handoff, unpublished case: $scene
                |orderId=${confirmedOrder.id}, pgOrderId=${payment.pgOrderId}, transactionKey=${payment.transactionKey}
                |eventId=${event.eventId}, confirmedAt=${event.confirmedAt}, items=${event.items}
                |order=${confirmedOrder.status}, payment=${payment.status}, stock=$stock
                |records on ${orderEventsProperties.topic}=$recordsOnTheTopic
                """.trimMargin()
            }
        }
    }
}
