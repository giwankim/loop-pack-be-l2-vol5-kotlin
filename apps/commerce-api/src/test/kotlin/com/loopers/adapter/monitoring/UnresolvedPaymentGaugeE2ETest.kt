package com.loopers.adapter.monitoring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.payment.PaymentQueryService
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.test.BaseRealPortWebApiAdapterTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.util.UUID

/**
 * The `commerce.payment.unresolved` gauge as a scraper reads it: the Prometheus text fetched over real HTTP from the injected
 * management port (spec 266, stories 1–7). No scene reads the gauge from a bean. Orders are confirmed and reconciled through
 * [mvc] against the test PG.
 *
 * The gauge counts committed rows on the scheduler thread, so the class leaves the test transaction and empties the tables with
 * [DatabaseCleanUp] (`OrderConfirmationApiTest`'s pattern). A changed count reaches the text one refresh later, so a scene waits
 * for it within [SCRAPE_WAIT].
 */
@EnableTestPg
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UnresolvedPaymentGaugeE2ETest(
    private val databaseCleanUp: DatabaseCleanUp,
) : BaseRealPortWebApiAdapterTest() {
    companion object {
        /** The card every confirmation sends. The number passes the Luhn check. The create stubs match this card. */
        private val CARD = CreditCard(type = "SAMSUNG", number = "4111-1111-1111-1111")

        private const val TRANSACTION_KEY = "20261126:TR:gauge1"

        /** Many refresh intervals of the real-port base, so a scene never waits on a slow tick by chance. */
        private val SCRAPE_WAIT: Duration = Duration.ofSeconds(10)
    }

    /** Field injection, not the constructor. The reason is on the same field in `OrderPaymentIntegrationTest`. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /** Where the failed-count scene makes the gauge's count throw. Unstubbed, it is the real count. */
    @MockkSpyBean
    private lateinit var paymentQueryService: PaymentQueryService

    private lateinit var owner: User

    /** The gauge's WARN on a failed count, from the adapter's package logger. */
    private val monitoringLogs = ListAppender<ILoggingEvent>()

    private val monitoringLogger = LoggerFactory.getLogger("com.loopers.adapter.monitoring") as Logger

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        owner = prepareUser()
        monitoringLogs.start()
        monitoringLogger.addAppender(monitoringLogs)
    }

    @AfterEach
    fun cleanUp() {
        monitoringLogger.detachAppender(monitoringLogs)
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `the Prometheus text holds the gauge for both unresolved statuses while health is UP`() {
        val health = managementGet("/actuator/health")
        val prometheus = managementGet("/actuator/prometheus")

        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).contains("\"status\":\"UP\"")
        assertThat(prometheus.statusCode()).isEqualTo(200)
        assertThat(prometheus.body())
            .contains("commerce_payment_unresolved{application=\"commerce-api\",status=\"UNKNOWN\"}")
            .contains("commerce_payment_unresolved{application=\"commerce-api\",status=\"PENDING\"}")
        assertThat(prometheus.body().lineSequence().single { it.startsWith("# HELP commerce_payment_unresolved ") })
            .contains("unresolved payments", "one interval old", "max, not sum")
    }

    /**
     * The incident in one order (spec 266, story 27): the create's response is lost, so the payment is 결과 미확인 and the gauge
     * says 1 while health says UP. The customer's reconcile finds the successful PG transaction and the gauge returns to 0.
     * The create is never sent again.
     */
    @Test
    fun `a lost create response shows one unknown payment while health is UP, and the reconcile that finds it returns it to 0`() {
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        testPg.losesCreateResponse(pgOrderId, amount = 2_000, card = CARD)
        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)

        awaitScraped(status = "UNKNOWN", value = "1.0")
        val health = managementGet("/actuator/health")
        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).contains("\"status\":\"UP\"")
        assertThat(scraped(status = "PENDING")).isEqualTo("0.0")

        val succeeded = PgTransaction(
            transactionKey = TRANSACTION_KEY,
            pgOrderId = pgOrderId,
            amount = Money(2_000),
            status = PgStatus.SUCCESS,
        )
        testPg.hasTransactions(pgOrderId, succeeded)
        assertThat(requestReconcile(orderId)).hasStatusOk()

        awaitScraped(status = "UNKNOWN", value = "0.0")
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    @Test
    fun `a payment whose accepted create is still pending at the PG shows as one pending payment`() {
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        testPg.acceptsCreate(nextPgOrderId(), amount = 2_000, card = CARD, transactionKey = TRANSACTION_KEY, pgStatus = "PENDING")
        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)

        awaitScraped(status = "PENDING", value = "1.0")
        assertThat(scraped(status = "UNKNOWN")).isEqualTo("0.0")
    }

    /**
     * A count that can't be read is no value, never "0 unresolved" or the last count (spec 266, story 5). The count fails for as
     * long as the scene needs to see `NaN`: one failure is overwritten by the next tick, a scrape might never catch it.
     */
    @Test
    fun `a failed count turns both series to NaN with a WARN, and the next good count restores the values`() {
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        testPg.losesCreateResponse(nextPgOrderId(), amount = 2_000, card = CARD)
        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)
        awaitScraped(status = "UNKNOWN", value = "1.0")
        val countingService = AopTestUtils.getUltimateTargetObject<PaymentQueryService>(paymentQueryService)

        every { countingService.countUnresolved() } throws DataAccessResourceFailureException("The database is gone.")

        awaitScraped(status = "UNKNOWN", value = "NaN")
        assertThat(scraped(status = "PENDING")).isEqualTo("NaN")
        assertThat(monitoringLogs.list.filter { it.level == Level.WARN }).isNotEmpty().allSatisfy({
            assertThat(it.throwableProxy.message).isEqualTo("The database is gone.")
        })

        every { countingService.countUnresolved() } answers { callOriginal() }

        awaitScraped(status = "UNKNOWN", value = "1.0")
        assertThat(scraped(status = "PENDING")).isEqualTo("0.0")
    }

    /** Waits until the scraped value of [status]'s series is [value], as Prometheus writes it (`1.0`, `NaN`). */
    private fun awaitScraped(status: String, value: String) {
        await().atMost(SCRAPE_WAIT).until { scraped(status) == value }
    }

    /** The value of the gauge's [status] series in the Prometheus text scraped now, or `null` if the series is missing. */
    private fun scraped(status: String): String? {
        val series = "commerce_payment_unresolved{application=\"commerce-api\",status=\"$status\"} "
        val line = managementGet("/actuator/prometheus").body().lineSequence().firstOrNull { it.startsWith(series) }

        return line?.removePrefix(series)
    }

    private fun requestConfirm(orderId: Long): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .header(UserIdHeader.NAME, owner.id)
            .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"cardType":"${CARD.type}","cardNo":"${CARD.number}"}""")
            .exchange()
    }

    private fun requestReconcile(orderId: Long): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/payment/reconcile").header(UserIdHeader.NAME, owner.id).exchange()
    }
}
