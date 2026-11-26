package com.loopers.adapter.webapi.v1.order

import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.annotation.JsonInclude
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.jayway.jsonpath.JsonPath
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.user.User
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.EnableTestPg
import com.loopers.support.IncidentPg
import com.loopers.support.TestPg
import com.loopers.support.logEventsOf
import com.loopers.support.test.BaseRealPortWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.wiremock.spring.InjectWireMock
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The payment incident of the week 10 assignment as one lifecycle (spec 274): one `@Test` that later tickets extend, so the same
 * database and the same PG transactions carry through every phase.
 *
 * - Baseline: 10 normal orders. Each create is accepted as `PENDING`, the test plays that order's PG 알림 right after its
 *   confirmation, and the lookup finds `SUCCESS`.
 * - Fault: 10 other orders under response loss. The 3rd, 6th and 9th lose their create's response, while the PG holds their
 *   `SUCCESS` transactions. The test plays no 알림 for them, and nothing else looks them up. The other 7 finish like baseline orders.
 *
 * Each phase reads its record fresh from the database, the test PG's request log, and one scrape of the management port, writes it
 * to the run's evidence directory ([IncidentEvidence]), and asserts on that record. Health and Prometheus are written as fetched.
 * No status is written into the commerce database: orders move only through [mvc]. Every identifier asserted or written is read
 * back from the database or the PG.
 *
 * A failure anywhere in the phases writes `failure.json` and deletes nothing. The `finally` also releases response loss and checks
 * that no PG order ID received a second create. A failure before the run directory exists (`@BeforeEach`) has nowhere to write.
 *
 * Confirmations commit per request and the gauge counts committed rows, so the class leaves the test transaction and empties the
 * tables itself (`UnresolvedPaymentGaugeE2ETest`'s pattern).
 */
@EnableTestPg
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentIncidentE2ETest(
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : BaseRealPortWebApiAdapterTest() {
    companion object {
        /** The card every confirmation sends. The number passes the Luhn check. The fixture's create stubs match this card. */
        private val CARD = CreditCard(type = "SAMSUNG", number = "4111-1111-1111-1111")

        /** One unit of the one product, so every order's PG amount. */
        private const val PRICE = 1_000L

        private const val ORDERS_PER_PHASE = 10

        /** Stock for the 20 orders of both phases, one unit each, and no more. */
        private const val STOCK = 2 * ORDERS_PER_PHASE

        /** The fault's lost orders by their place in the fault phase, counted from 1: the 3rd, 6th and 9th. */
        private val LOST_PLACES = listOf(3, 6, 9)

        /** The owner's starting point balance. No order uses it. */
        private const val POINT_BALANCE = 50_000L

        /** Many refresh intervals of the real-port base, so a phase never waits on a slow tick by chance. */
        private val SCRAPE_WAIT: Duration = Duration.ofSeconds(10)

        private val GAUGE_STATUSES = listOf("UNKNOWN", "PENDING")
    }

    /** Field injection, not the constructor. The reason is on the same field in `OrderPaymentIntegrationTest`. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val incidentPg by lazy { IncidentPg(server) }

    private lateinit var owner: User

    /** The saga's log events, from the order application package's logger. */
    private val orderApplicationLogs = ListAppender<ILoggingEvent>()

    private val orderApplicationLogger = LoggerFactory.getLogger("com.loopers.application.order") as Logger

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        owner = prepareUser()
        charge(POINT_BALANCE, owner)
        prepareCoupon(prepareCouponCampaign(), owner)
        prepareProduct(price = PRICE, stock = STOCK)
        orderApplicationLogs.start()
        orderApplicationLogger.addAppender(orderApplicationLogs)
    }

    @AfterEach
    fun cleanUp() {
        orderApplicationLogger.detachAppender(orderApplicationLogs)
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `a lost create response leaves three payments unknown while health is UP, and the evidence of each phase is kept`() {
        val evidence = IncidentEvidence.start(objectMapper, Instant.now())
        var phase = "baseline"
        var failure: Throwable? = null
        try {
            val baseline = runBaseline(evidence)

            phase = "fault"
            runFault(evidence, baseline)
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            incidentPg.stopResponseLoss()
            val createdTwice = incidentPg.pgOrderIdsCreatedMoreThanOnce()
            val duplicate = if (createdTwice.isEmpty()) {
                null
            } else {
                AssertionError("PG order IDs that received more than one create: $createdTwice")
            }
            val caught = failure
            if (caught != null && duplicate != null) {
                caught.addSuppressed(duplicate)
            }
            val ended = caught ?: duplicate
            if (ended != null) {
                evidence.writeJson("failure.json", FailureRecord.of(phase, ended))
            }
            if (caught == null && duplicate != null) {
                throw duplicate
            }
        }
    }

    /** 10 normal orders, each finished by its PG 알림. Returns the phase's record, which `baseline.json` holds. */
    private fun runBaseline(evidence: IncidentEvidence): PhaseRecord {
        assertThat(incidentPg.isResponseLossOn()).isFalse()
        val orderIds = createOrders(ORDERS_PER_PHASE, existingOrderIds = emptyList())
        orderIds.forEach { orderId ->
            incidentPg.registerNormal(nextPgOrderId(), amount = PRICE, card = CARD)
            assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)
            assertThat(requestCallback(orderId)).hasStatusOk()
        }

        val record = observe(evidence, "baseline", orderIds, lostOrderIds = emptyList(), expectedUnknown = "0.0")

        assertThat(record.responseLossOn).isFalse()
        assertThat(record.orders).hasSize(ORDERS_PER_PHASE).allSatisfy({
            assertThat(it.orderStatus).isEqualTo("CONFIRMED")
            assertThat(it.paymentStatus).isEqualTo("SUCCESS")
            assertThat(it.createCount).isOne()
        })
        assertThat(record.unresolvedGauge).isEqualTo(mapOf("UNKNOWN" to "0.0", "PENDING" to "0.0"))
        assertThat(record.healthStatus).isEqualTo("UP")
        assertThat(record.pgTransactionCount).isEqualTo(ORDERS_PER_PHASE)
        assertThat(record.pgCreateCount).isEqualTo(ORDERS_PER_PHASE)
        assertThat(record.unresolvedOrderIds).isEmpty()
        assertThat(record.productStock).isEqualTo(STOCK - ORDERS_PER_PHASE)
        assertThat(record.pgOrderIdsCreatedMoreThanOnce).isEmpty()
        assertThat(record.unmatchedPgRequests).isZero()

        return record
    }

    /**
     * 10 other orders under response loss. Every PG transaction of the phase, the lost orders' `SUCCESS` ones included, is
     * registered before the loss starts. Returns the phase's record, which `fault.json` holds.
     */
    private fun runFault(evidence: IncidentEvidence, baseline: PhaseRecord): PhaseRecord {
        val orderIds = createOrders(ORDERS_PER_PHASE, existingOrderIds = baseline.orderIds)
        val lostOrderIds = LOST_PLACES.map { orderIds[it - 1] }
        // The generator hands out PG order IDs in the order they were drawn, and confirmations run one by one in this order.
        orderIds.forEach { orderId ->
            if (orderId in lostOrderIds) {
                incidentPg.registerLost(nextPgOrderId(), amount = PRICE, card = CARD)
            } else {
                incidentPg.registerNormal(nextPgOrderId(), amount = PRICE, card = CARD)
            }
        }

        incidentPg.startResponseLoss()
        orderIds.forEach { orderId ->
            assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)
            if (orderId !in lostOrderIds) {
                assertThat(requestCallback(orderId)).hasStatusOk()
            }
        }

        val record = observe(evidence, "fault", orderIds, lostOrderIds, expectedUnknown = "3.0")
        evidence.writeJson("fault-log-events.json", lostOrderIds.associate { it.toString() to logEventRecords(it) })
        incidentPg.stopResponseLoss()

        val lost = record.orders.filter { it.orderId in record.lostOrderIds }
        val normal = record.orders.filterNot { it.orderId in record.lostOrderIds }
        assertThat(record.responseLossOn).isTrue()
        assertThat(record.lostOrderIds).hasSize(LOST_PLACES.size)
        assertThat(normal).hasSize(ORDERS_PER_PHASE - LOST_PLACES.size).allSatisfy({
            assertThat(it.orderStatus).isEqualTo("CONFIRMED")
            assertThat(it.paymentStatus).isEqualTo("SUCCESS")
            assertThat(it.createCount).isOne()
        })
        assertThat(lost).hasSize(LOST_PLACES.size).allSatisfy({
            assertThat(it.orderStatus).isEqualTo("PAYMENT_PENDING")
            assertThat(it.paymentStatus).isEqualTo("UNKNOWN")
            assertThat(it.reservedQuantity).isOne()
            assertThat(it.createCount).isOne()
            assertThat(it.listLookupCount).isZero()
        })
        assertThat(record.unresolvedOrderIds).isEqualTo(record.lostOrderIds)
        assertThat(record.unresolvedGauge).isEqualTo(mapOf("UNKNOWN" to "3.0", "PENDING" to "0.0"))
        assertThat(record.healthStatus).isEqualTo("UP")
        assertThat(record.pgTransactionCount).isEqualTo(2 * ORDERS_PER_PHASE)
        assertThat(record.pgCreateCount).isEqualTo(2 * ORDERS_PER_PHASE)
        assertThat(record.productStock).isZero()
        assertThat(record.pgOrderIdsCreatedMoreThanOnce).isEmpty()
        assertThat(record.unmatchedPgRequests).isZero()

        return record
    }

    /**
     * Reads the phase's record and writes `<phase>.json`, `<phase>-health.json` and `<phase>-prometheus.txt`. The scrape is
     * repeated until the gauge's `UNKNOWN` series reads [expectedUnknown] and `PENDING` reads `0.0`, since a changed count reaches
     * the text one refresh later. The files hold the last scrape, the one the record's gauge and health come from.
     */
    private fun observe(
        evidence: IncidentEvidence,
        phase: String,
        orderIds: List<Long>,
        lostOrderIds: List<Long>,
        expectedUnknown: String,
    ): PhaseRecord {
        var last: Scrape? = null
        await().atMost(SCRAPE_WAIT).untilAsserted {
            val current = scrape()
            last = current
            assertThat(current.gauge).isEqualTo(mapOf("UNKNOWN" to expectedUnknown, "PENDING" to "0.0"))
        }
        val scraped = checkNotNull(last)
        val record = PhaseRecord(
            phase = phase,
            observedAt = Instant.now().toString(),
            responseLossOn = incidentPg.isResponseLossOn(),
            orderIds = orderIds,
            lostOrderIds = lostOrderIds,
            unresolvedOrderIds = unresolvedOrderIds(),
            orders = readOrders(orderIds),
            productStock = productStock(),
            pgTransactionCount = incidentPg.createdPgOrderIds().size,
            pgCreateCount = incidentPg.createCount(),
            pgOrderIdsCreatedMoreThanOnce = incidentPg.pgOrderIdsCreatedMoreThanOnce().sorted(),
            unmatchedPgRequests = incidentPg.unmatchedRequests().size,
            healthStatus = scraped.healthStatus,
            unresolvedGauge = scraped.gauge,
        )
        evidence.writeJson("$phase.json", record)
        evidence.writeText("$phase-health.json", scraped.health)
        evidence.writeText("$phase-prometheus.txt", scraped.prometheus)

        return record
    }

    /** Health and Prometheus fetched once each from the management port, and what the record reads from them. */
    private fun scrape(): Scrape {
        val health = managementGet("/actuator/health")
        val prometheus = managementGet("/actuator/prometheus")
        check(health.statusCode() == 200) { "Health answered ${health.statusCode()}: ${health.body()}" }
        check(prometheus.statusCode() == 200) { "Prometheus answered ${prometheus.statusCode()}" }
        val gauge = GAUGE_STATUSES.associateWith { status ->
            val series = "commerce_payment_unresolved{application=\"commerce-api\",status=\"$status\"} "
            prometheus.body().lineSequence().firstOrNull { it.startsWith(series) }?.removePrefix(series)
        }

        return Scrape(
            health = health.body(),
            healthStatus = JsonPath.read(health.body(), "$.status"),
            prometheus = prometheus.body(),
            gauge = gauge,
        )
    }

    /**
     * Creates [count] orders of one unit of the product for the owner, and returns their IDs as the database holds them: the
     * owner's orders not in [existingOrderIds], in ID order.
     */
    private fun createOrders(count: Int, existingOrderIds: List<Long>): List<Long> {
        repeat(count) { prepareOrder(owner, listOf(product), quantity = 1) }
        val sql = "select id from orders where user_id = ? order by id"
        val orderIds = jdbc.queryForList(sql, Long::class.javaObjectType, owner.id)
            .filterNotNull()
            .filterNot { it in existingOrderIds }
        check(orderIds.size == count) { "Expected $count new orders, found $orderIds." }

        return orderIds
    }

    /**
     * Each order's row with its one line item and its payment, read fresh. The reservation is the line's quantity while the order
     * is payment pending (CONTEXT.md, 재고 예약): it was taken at the confirmation and is neither consumed nor released yet. It
     * follows from the order's status, so the record's product stock is what shows the reservations really held.
     */
    private fun readOrders(orderIds: List<Long>): List<OrderRecord> {
        val placeholders = orderIds.joinToString(",") { "?" }
        val sql = """
            select o.id, o.status, li.quantity, p.idempotency_key, p.pg_order_id, p.transaction_key, p.status as payment_status
            from orders o
            join order_line_item li on li.order_id = o.id
            left join payment p on p.order_id = o.id
            where o.id in ($placeholders)
            order by o.id
        """

        val mapper = RowMapper { rs, _ ->
            val orderStatus = rs.getString("status")
            val pgOrderId = rs.getString("pg_order_id")

            OrderRecord(
                orderId = rs.getLong("id"),
                idempotencyKey = rs.getString("idempotency_key"),
                pgOrderId = pgOrderId,
                transactionKey = rs.getString("transaction_key"),
                orderStatus = orderStatus,
                paymentStatus = rs.getString("payment_status"),
                reservedQuantity = if (orderStatus == "PAYMENT_PENDING") rs.getInt("quantity") else 0,
                createCount = pgOrderId?.let { incidentPg.createCount(it) } ?: 0,
                listLookupCount = pgOrderId?.let { incidentPg.listLookupCount(it) } ?: 0,
            )
        }

        return jdbc.query(sql, mapper, *orderIds.toTypedArray())
    }

    /** The order IDs of the unresolved payments in the database, 결과 미확인 or 처리 중, in order ID order. */
    private fun unresolvedOrderIds(): List<Long> {
        return jdbc.queryForList(
            "select order_id from payment where status in ('UNKNOWN', 'PENDING') order by order_id",
            Long::class.javaObjectType,
        ).filterNotNull()
    }

    private fun productStock(): Int {
        return jdbc.queryForObject("select stock from product where id = ?", Int::class.javaObjectType, product.id)!!
    }

    /** One order's captured log events, as `fault-log-events.json` holds them. */
    private fun logEventRecords(orderId: Long): List<Map<String, Any?>> {
        return orderApplicationLogs.logEventsOf(orderId).map { event ->
            mapOf(
                "level" to event.level.toString(),
                "fields" to event.fields,
                "exception" to event.throwable?.let { "${it.javaClass.name}: ${it.message}" },
            )
        }
    }

    private fun requestConfirm(orderId: Long): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .header(UserIdHeader.NAME, owner.id)
            .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"cardType":"${CARD.type}","cardNo":"${CARD.number}"}""")
            .exchange()
    }

    /**
     * The PG's 알림 for [orderId]'s payment: the PG order ID read from the payment row, and the transaction the PG holds under it.
     * The status is `SUCCESS`, the transaction's as the PG holds it. Commerce reads only the PG order ID from the body and finds
     * the rest by its own lookup (ADR 0031).
     */
    private fun requestCallback(orderId: Long): MvcTestResult {
        val pgOrderId = jdbc.queryForObject("select pg_order_id from payment where order_id = ?", String::class.java, orderId)!!
        val transaction = incidentPg.transactions().single { it.pgOrderId == pgOrderId }

        val transactionKey = transaction.transactionKey
        val body = """{"transactionKey":"$transactionKey","orderId":"$pgOrderId","status":"${transaction.status}"}"""

        return mvc.post().uri("/api/v1/payments/callback")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .exchange()
    }
}

/** What a phase observed, as `<phase>.json` holds it and the phase asserts it. */
private data class PhaseRecord(
    val phase: String,
    val observedAt: String,
    val responseLossOn: Boolean,
    val orderIds: List<Long>,
    val lostOrderIds: List<Long>,
    /** The order IDs of every unresolved payment in the database, whichever phase made it. */
    val unresolvedOrderIds: List<Long>,
    val orders: List<OrderRecord>,
    val productStock: Int,
    /**
     * The PG transactions the test PG made, from its request log: one per PG order ID that received a create. Every create matched
     * a stub (`unmatchedPgRequests` is 0), and the PG makes the transaction whether its answer is sent or lost.
     */
    val pgTransactionCount: Int,
    /** The creates in the test PG's request log, answered or not. */
    val pgCreateCount: Int,
    val pgOrderIdsCreatedMoreThanOnce: List<String>,
    val unmatchedPgRequests: Int,
    val healthStatus: String,
    /** The scraped `commerce_payment_unresolved` value by `status`, as Prometheus writes it, or `null` for a missing series. */
    val unresolvedGauge: Map<String, String?>,
)

/**
 * One order as its phase observed it. Every field is written, `null` included: a lost order's payment has no transaction key, and
 * the file says so rather than leaving the field out.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
private data class OrderRecord(
    val orderId: Long,
    val idempotencyKey: String?,
    val pgOrderId: String?,
    val transactionKey: String?,
    val orderStatus: String,
    val paymentStatus: String?,
    val reservedQuantity: Int,
    /** The creates the test PG received for the order's PG order ID. */
    val createCount: Int,
    /** The list lookups the test PG received for the order's PG order ID. */
    val listLookupCount: Int,
)

/** One scrape of the management port. */
private data class Scrape(
    val health: String,
    val healthStatus: String,
    val prometheus: String,
    val gauge: Map<String, String?>,
)

/**
 * The evidence directory of one run (spec 274, "증거"): `build/payment-incident/<run id>/` under the module, the test's working
 * directory. The run ID is the start time in UTC and a random suffix, and the directory is created new, so two runs write two
 * directories and a run never writes into another's. Every file is created new: writing a name twice fails instead of
 * overwriting the first record. Nothing here deletes a file.
 */
private class IncidentEvidence private constructor(
    private val directory: Path,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private val ROOT: Path = Path.of("build", "payment-incident")

        private val RUN_ID_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** Creates the run's directory. Fails if it already exists. */
        fun start(objectMapper: ObjectMapper, startedAt: Instant): IncidentEvidence {
            Files.createDirectories(ROOT)
            val runId = "${RUN_ID_TIME.format(startedAt)}-${UUID.randomUUID().toString().take(8)}"
            val directory = Files.createDirectory(ROOT.resolve(runId))

            return IncidentEvidence(directory, objectMapper)
        }
    }

    /** Writes [text] as it is to a new file [name]. Fails if the file exists. */
    fun writeText(name: String, text: String) {
        Files.writeString(directory.resolve(name), text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    /** Writes [value] as indented JSON to a new file [name]. Fails if the file exists. */
    fun writeJson(name: String, value: Any) {
        writeText(name, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))
    }
}

/** `failure.json`: the phase that was running and the exception that ended the run. */
private data class FailureRecord(
    val phase: String,
    val exception: String,
    val message: String?,
    val stackTrace: String,
) {
    companion object {
        fun of(phase: String, exception: Throwable): FailureRecord {
            return FailureRecord(
                phase = phase,
                exception = exception.javaClass.name,
                message = exception.message,
                stackTrace = exception.stackTraceToString(),
            )
        }
    }
}
