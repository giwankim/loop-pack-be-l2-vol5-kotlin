package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.payment.PaymentStatus
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.linesAt
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.errors.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.kafka.core.KafkaProducerException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.TestPropertySource
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * A send that fails is that row's failure, whichever way it fails, and costs the customer nothing (ADR 0035 rule 4). The shared broker
 * can't be paused without affecting other classes, so the failure is injected at the template, on the spy of the shared bean: a failed
 * future wraps its cause the way the template's own callback does. Everything past the template is real, so a healthy row reaches the
 * broker. This is a class of its own because the spy makes a context of its own. Rows are read in a new transaction.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=order-events-relay-failed-send"])
class OrderEventRelayFailedSendTest(
    private val orderEventRelayer: OrderEventRelayer,
    private val orderFinder: OrderFinder,
    private val paymentFinder: PaymentFinder,
    private val productFinder: ProductFinder,
    private val pointAccountFinder: PointAccountFinder,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
        private val ROUND_STARTED_AT: Instant = Instant.parse("2026-10-27T01:02:08.123456Z")
        private const val TIMEOUT_MESSAGE = "Expiration of 1 record(s) for order-events-relay-failed-send-0"
    }

    @MockkSpyBean
    private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

    /** The test PG's server. Like [OrderPaymentIntegrationTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    private lateinit var orderEvents: TopicReader

    private val kafkaAdapterLogs = ListAppender<ILoggingEvent>()

    private val kafkaAdapterLogger = LoggerFactory.getLogger("com.loopers.adapter.integration.kafka") as Logger

    @BeforeEach
    fun readTheTopicFromItsEnd() {
        orderEvents = TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic)
    }

    @BeforeEach
    fun captureKafkaAdapterLogs() {
        kafkaAdapterLogs.start()
        kafkaAdapterLogger.addAppender(kafkaAdapterLogs)
    }

    @AfterEach
    fun closeTheTopicReader() {
        orderEvents.close()
    }

    @AfterEach
    fun releaseKafkaAdapterLogs() {
        kafkaAdapterLogger.detachAppender(kafkaAdapterLogs)
        kafkaAdapterLogs.stop()
    }

    @Test
    fun `a failed future is that row's failure, named by its cause, and the rest of the batch is published`() {
        prepareOrderEventOutbox(orderId = 201, nextAttemptAt = CONFIRMED_AT)
        prepareOrderEventOutbox(orderId = 202, nextAttemptAt = CONFIRMED_AT)
        every { kafkaTemplate.send(any<String>(), "201", any<Any>()) } returns failedSend()
        clock.pin(ROUND_STARTED_AT)

        orderEventRelayer.publishDueEvents()

        val failedOutbox = committedOrderEventOutbox(orderId = 201)
        assertThat(failedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(failedOutbox.attemptCount).isOne()
        assertThat(failedOutbox.lastError).isEqualTo("org.apache.kafka.common.errors.TimeoutException: $TIMEOUT_MESSAGE")
        assertThat(failedOutbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:13.123456Z"))
        assertThat(committedOrderEventOutbox(orderId = 202).status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(orderEvents.publishedRecords(expected = 1).map { it.key() }).containsExactly("202")
        assertThat(kafkaAdapterLogs.linesAt(Level.WARN).map { it.formattedMessage }).containsExactly(
            "Failed to publish the order confirmed event. eventId=order-confirmed:v1:201, " +
                "cause=org.apache.kafka.common.errors.TimeoutException: $TIMEOUT_MESSAGE",
        )
    }

    @Test
    fun `a send that throws is that row's failure, and the rest of the batch is published`() {
        prepareOrderEventOutbox(orderId = 201, nextAttemptAt = CONFIRMED_AT)
        prepareOrderEventOutbox(orderId = 202, nextAttemptAt = CONFIRMED_AT)
        val refusal = SerializationException("The producer refused the record.")
        every { kafkaTemplate.send(any<String>(), "201", any<Any>()) } throws refusal
        clock.pin(ROUND_STARTED_AT)

        orderEventRelayer.publishDueEvents()

        val failedOutbox = committedOrderEventOutbox(orderId = 201)
        assertThat(failedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(failedOutbox.lastError)
            .isEqualTo("org.apache.kafka.common.errors.SerializationException: The producer refused the record.")
        assertThat(committedOrderEventOutbox(orderId = 202).status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(orderEvents.publishedRecords(expected = 1).map { it.key() }).containsExactly("202")
    }

    /** They stay as the evidence of the retry that came before the publish (ADR 0035 rule 3). */
    @Test
    fun `a row that later succeeds keeps its attempt count and its last error`() {
        prepareOrderEventOutbox(orderId = 201, nextAttemptAt = CONFIRMED_AT)
        every { kafkaTemplate.send(any<String>(), "201", any<Any>()) } returns failedSend() andThenAnswer { callOriginal() }
        clock.pin(ROUND_STARTED_AT)
        orderEventRelayer.publishDueEvents()

        clock.advance(Duration.ofSeconds(5))
        orderEventRelayer.publishDueEvents()

        val publishedOutbox = committedOrderEventOutbox(orderId = 201)
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:02:13.123456Z"))
        assertThat(publishedOutbox.attemptCount).isEqualTo(2)
        assertThat(publishedOutbox.lastError).isEqualTo("org.apache.kafka.common.errors.TimeoutException: $TIMEOUT_MESSAGE")
        assertThat(orderEvents.publishedRecords(expected = 1).map { it.key() }).containsExactly("201")
    }

    @Test
    fun `a failure leaves the order, payment, stock and points unchanged and creates no PG transaction`() {
        prepareOrder()
        charge(amount = 10_000)
        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg)
        val purchaseBeforeTheFailure = committedPurchase()
        val pgRequestsBeforeTheFailure = testPg.requestCount()
        every { kafkaTemplate.send(any<String>(), order.id.toString(), any<Any>()) } returns failedSend()
        clock.pin(ROUND_STARTED_AT)

        orderEventRelayer.publishDueEvents()

        val failedOutbox = committedOrderEventOutbox(orderId = order.id)
        assertThat(failedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(failedOutbox.lastError).isNotNull()
        assertThat(committedPurchase()).isEqualTo(purchaseBeforeTheFailure)
        assertThat(purchaseBeforeTheFailure.orderStatus).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(purchaseBeforeTheFailure.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testPg.requestCount()).isEqualTo(pgRequestsBeforeTheFailure)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** Fails as the template's own callback does: the future holds a [KafkaProducerException] around the producer's exception. */
    private fun failedSend(): CompletableFuture<SendResult<Any, Any>> {
        val producerRecord = ProducerRecord<Any, Any>(orderEventsProperties.topic, "key", "value")

        return CompletableFuture.failedFuture(
            KafkaProducerException(producerRecord, "Failed to send", TimeoutException(TIMEOUT_MESSAGE)),
        )
    }

    private fun committedPurchase(): CommittedPurchase {
        return inNewTransaction {
            CommittedPurchase(
                orderStatus = orderFinder.find(user.id, order.id).status,
                paymentStatus = paymentFinder.findByOrder(order.id).status,
                stock = productFinder.find(product.id).stock,
                pointBalance = pointAccountFinder.findByUser(user.id).balance.amount.longValueExact(),
            )
        }
    }

    private data class CommittedPurchase(
        val orderStatus: OrderStatus,
        val paymentStatus: PaymentStatus,
        val stock: Int,
        val pointBalance: Long,
    )
}
