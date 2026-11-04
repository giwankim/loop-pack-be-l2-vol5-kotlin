package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.OrderEventOutboxModifyService
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
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaProducerException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.TestPropertySource
import org.wiremock.spring.InjectWireMock
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import kotlin.io.path.readText

/**
 * A send that fails is that row's failure, whichever way it fails, and costs the customer nothing (ADR 0035 rule 4). The shared broker
 * can't be paused without affecting other classes, so the failure is injected at the template, on the spy of the shared bean: a failed
 * future wraps its cause the way the template's own callback does. Everything past the template is real, so a healthy row reaches the
 * broker. This is a class of its own because the spy makes a context of its own. Rows are read in a new transaction.
 *
 * Failures that go on end in `BLOCKED` (rule 7), and the same spy is how a blocked row later finds a valid path to the broker, since its
 * `payload` never changes. So the manual resume and backlog SQL are shown here too (rule 8).
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
    private val orderEventOutboxModifyService: OrderEventOutboxModifyService,
    private val jdbc: JdbcTemplate,
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

    /** Logs from the application's order package, where the row's transition to `BLOCKED` is seen. */
    private val orderApplicationLogs = ListAppender<ILoggingEvent>()

    private val orderApplicationLogger = LoggerFactory.getLogger("com.loopers.application.order") as Logger

    @BeforeEach
    fun readTheTopicFromItsEnd() {
        orderEvents = TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic)
    }

    @BeforeEach
    fun captureLogs() {
        kafkaAdapterLogs.start()
        kafkaAdapterLogger.addAppender(kafkaAdapterLogs)
        orderApplicationLogs.start()
        orderApplicationLogger.addAppender(orderApplicationLogs)
    }

    @AfterEach
    fun closeTheTopicReader() {
        orderEvents.close()
    }

    @AfterEach
    fun releaseLogs() {
        kafkaAdapterLogger.detachAppender(kafkaAdapterLogs)
        kafkaAdapterLogs.stop()
        orderApplicationLogger.detachAppender(orderApplicationLogs)
        orderApplicationLogs.stop()
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

    @Test
    fun `a row failing 20 times is blocked with its last error and isn't claimed again`() {
        blockOutboxThroughFailedRounds(orderId = 201)

        val blockedOutbox = committedOrderEventOutbox(orderId = 201)
        assertThat(blockedOutbox.status).isEqualTo(OrderEventOutboxStatus.BLOCKED)
        assertThat(blockedOutbox.attemptCount).isEqualTo(20)
        assertThat(blockedOutbox.lastError).isEqualTo("org.apache.kafka.common.errors.TimeoutException: $TIMEOUT_MESSAGE")
        assertThat(blockedOutbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T02:38:08.123456Z"))

        orderEventRelayer.publishDueEvents()

        assertThat(committedOrderEventOutbox(orderId = 201).attemptCount).isEqualTo(20)
        assertThat(orderEvents.publishedRecords(expected = 0)).isEmpty()
    }

    /** The ordinary line stays on all 20 failures. The other names what changed, and comes once. */
    @Test
    fun `the failure that blocks a row logs a warn distinct from an ordinary failure, naming the event and the cause`() {
        blockOutboxThroughFailedRounds(orderId = 201)

        assertThat(kafkaAdapterLogs.linesAt(Level.WARN)).hasSize(20)
        assertThat(orderApplicationLogs.linesAt(Level.WARN).map { it.formattedMessage }).containsExactly(
            "Stopped retrying the order confirmed event: it stays BLOCKED until someone resumes it by hand. " +
                "eventId=order-confirmed:v1:201, attempts=20, " +
                "cause=org.apache.kafka.common.errors.TimeoutException: $TIMEOUT_MESSAGE",
        )
    }

    /** A relay past its lease can report a failure after another relay blocked the row. A round can't stage that, so the scene makes the late call itself. */
    @Test
    fun `a late failure on a row already blocked leaves its error and logs no second transition`() {
        blockOutboxThroughFailedRounds(orderId = 201)
        val blockedOutbox = committedOrderEventOutbox(orderId = 201)

        orderEventOutboxModifyService.markFailed(blockedOutbox.id, "a late failure")

        assertThat(committedOrderEventOutbox(orderId = 201).lastError).isEqualTo(blockedOutbox.lastError)
        assertThat(orderApplicationLogs.linesAt(Level.WARN)).hasSize(1)
    }

    @Test
    fun `other rows keep publishing while one is blocked`() {
        blockOutboxThroughFailedRounds(orderId = 201)
        prepareOrderEventOutbox(orderId = 202, nextAttemptAt = CONFIRMED_AT)

        orderEventRelayer.publishDueEvents()

        assertThat(committedOrderEventOutbox(orderId = 202).status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(committedOrderEventOutbox(orderId = 201).status).isEqualTo(OrderEventOutboxStatus.BLOCKED)
        assertThat(orderEvents.publishedRecords(expected = 1).map { it.key() }).containsExactly("202")
    }

    /**
     * This scene writes `status`, and that is the point of it. The rule against a test overwriting `status` is about the stop scenes,
     * where it would fake a recovery. Here it runs the documented manual procedure (order-outbox design 13.7), read from the document,
     * so the statement can't go stale. Its time is the database's, not the [clock]'s, so the scene moves the clock to it.
     */
    @Test
    fun `the documented resume statement leads a blocked row to published on the next round, keeping its attempts`() {
        blockOutboxThroughFailedRounds(orderId = 201)
        letSendsThrough(orderId = 201)

        resumeAsTheDesignDocumentSays(orderId = 201)

        val resumedOutbox = committedOrderEventOutbox(orderId = 201)
        assertThat(resumedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(resumedOutbox.attemptCount).isEqualTo(20)
        assertThat(resumedOutbox.lastError).isEqualTo("org.apache.kafka.common.errors.TimeoutException: $TIMEOUT_MESSAGE")

        clock.pin(resumedOutbox.nextAttemptAt)
        orderEventRelayer.publishDueEvents()

        val publishedOutbox = committedOrderEventOutbox(orderId = 201)
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.attemptCount).isEqualTo(21)
        assertThat(orderEvents.publishedRecords(expected = 1).map { it.key() }).containsExactly("201")
    }

    /** The count stays, since it is the real number of attempts, so one more failure is over the limit at once (ADR 0035 rule 8). */
    @Test
    fun `a resumed row that fails again goes straight back to blocked`() {
        blockOutboxThroughFailedRounds(orderId = 201)

        resumeAsTheDesignDocumentSays(orderId = 201)
        clock.pin(committedOrderEventOutbox(orderId = 201).nextAttemptAt)
        orderEventRelayer.publishDueEvents()

        val blockedAgainOutbox = committedOrderEventOutbox(orderId = 201)
        assertThat(blockedAgainOutbox.status).isEqualTo(OrderEventOutboxStatus.BLOCKED)
        assertThat(blockedAgainOutbox.attemptCount).isEqualTo(21)
        assertThat(orderApplicationLogs.linesAt(Level.WARN)).hasSize(2)
    }

    /**
     * The columns hold UTC (`hibernate.jdbc.time_zone`) while a session may be in any zone, as a client on a server set to Seoul would
     * be. The relay's clock is the real one in operation, so a resumed row has to be due about now by that clock.
     */
    @Test
    fun `the documented resume statement makes a row due now in a session of any time zone`() {
        blockOutboxThroughFailedRounds(orderId = 201)

        inNewTransaction {
            jdbc.execute("set time_zone = '+09:00'")
            try {
                resumeAsTheDesignDocumentSays(orderId = 201)
            } finally {
                jdbc.execute("set time_zone = default")
            }
        }

        assertThat(committedOrderEventOutbox(orderId = 201).nextAttemptAt).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES))
    }

    /**
     * The design's backlog statements on rows the relay brought to each state. `created_at` follows the system clock, so the scene sets
     * it to literals. All four rows are still counted at the end, so neither publishing nor blocking removed one.
     */
    @Test
    fun `the documented backlog statements return the attempts, the pending count, the oldest wait and the blocked rows`() {
        blockOutboxThroughFailedRounds(orderId = 201)
        prepareOrderEventOutbox(orderId = 202, nextAttemptAt = CONFIRMED_AT)
        prepareOrderEventOutbox(orderId = 203, nextAttemptAt = CONFIRMED_AT)
        prepareOrderEventOutbox(orderId = 204, nextAttemptAt = clock.instant().plus(Duration.ofHours(1)))
        every { kafkaTemplate.send(any<String>(), "202", any<Any>()) } returns failedSend()
        orderEventRelayer.publishDueEvents()
        setCreatedAt(orderId = 201, createdAt = "2026-10-27T00:00:01")
        setCreatedAt(orderId = 202, createdAt = "2026-10-27T00:00:02.000002")
        setCreatedAt(orderId = 203, createdAt = "2026-10-27T00:00:03")
        setCreatedAt(orderId = 204, createdAt = "2026-10-27T00:00:04")

        val backlogRows = jdbc.query(designDocumentSql("상태마다의 행 수, 시도 수, 가장 오래된 행")) { row, _ ->
            BacklogRow(
                status = row.getString("status"),
                rowCount = row.getLong("row_count"),
                attempts = row.getLong("attempts"),
                oldestCreatedAt = row.getObject("oldest_created_at", LocalDateTime::class.java),
            )
        }
        val blockedRows = jdbc.queryForList(designDocumentSql("미해결 행"))

        assertThat(backlogRows).containsExactlyInAnyOrder(
            BacklogRow("PENDING", rowCount = 2, attempts = 1, LocalDateTime.parse("2026-10-27T00:00:02.000002")),
            BacklogRow("PUBLISHED", rowCount = 1, attempts = 1, LocalDateTime.parse("2026-10-27T00:00:03")),
            BacklogRow("BLOCKED", rowCount = 1, attempts = 20, LocalDateTime.parse("2026-10-27T00:00:01")),
        )
        val blockedRow = blockedRows.single()
        assertThat(blockedRow["event_id"]).isEqualTo("order-confirmed:v1:201")
        assertThat(blockedRow["order_id"]).isEqualTo(201L)
        assertThat(blockedRow["attempt_count"]).isEqualTo(20)
        assertThat(blockedRow["last_error"]).isEqualTo("org.apache.kafka.common.errors.TimeoutException: $TIMEOUT_MESSAGE")
        assertThat(blockedRow["updated_at"]).isNotNull()
    }

    /** The broker recovered, for this row's key: a later stub wins, and it lets the send through to the real template. */
    private fun letSendsThrough(orderId: Long) {
        every { kafkaTemplate.send(any<String>(), orderId.toString(), any<Any>()) } answers { callOriginal() }
    }

    private fun resumeAsTheDesignDocumentSays(orderId: Long) {
        val updatedRows = jdbc.update(designDocumentSql("한 행의 재개"), "order-confirmed:v1:$orderId")

        assertThat(updatedRows).isOne()
    }

    /**
     * The statement under the comment `-- `[title] in order-outbox design 13.7, without its semicolon. It is read rather than copied, so
     * a scene fails when the document says something that no longer works. Gradle runs tests from the app's directory, and the app's
     * build file declares the document as an input of the test task, so changing it runs them again.
     */
    private fun designDocumentSql(title: String): String {
        val sqlBlock = Path.of("../../docs/design/order-outbox.md").readText()
            .substringAfter("### 13.7 ")
            .substringAfter("```sql")
            .substringBefore("```")
        val comment = "-- $title\n"
        check(comment in sqlBlock) { "Design 13.7 has no statement titled '$title'." }

        return sqlBlock.substringAfter(comment).substringBefore(";")
    }

    private fun setCreatedAt(orderId: Long, createdAt: String) {
        jdbc.update(
            "update order_event_outbox set created_at = ? where event_id = ?",
            LocalDateTime.parse(createdAt),
            "order-confirmed:v1:$orderId",
        )
    }

    /** Fails every send of the row's key for as many rounds as the limit. The clock moves by the longest backoff after each, so each finds the row due. */
    private fun blockOutboxThroughFailedRounds(orderId: Long) {
        prepareOrderEventOutbox(orderId = orderId, nextAttemptAt = CONFIRMED_AT)
        every { kafkaTemplate.send(any<String>(), orderId.toString(), any<Any>()) } returns failedSend()
        clock.pin(ROUND_STARTED_AT)

        repeat(20) {
            orderEventRelayer.publishDueEvents()
            clock.advance(Duration.ofMinutes(5))
        }
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

    private data class BacklogRow(
        val status: String,
        val rowCount: Long,
        val attempts: Long,
        val oldestCreatedAt: LocalDateTime,
    )

    private data class CommittedPurchase(
        val orderStatus: OrderStatus,
        val paymentStatus: PaymentStatus,
        val stock: Int,
        val pointBalance: Long,
    )
}
