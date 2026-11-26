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
import com.loopers.support.logEvents
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
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID

/**
 * The payment incident of the week 10 assignment as one lifecycle (spec 274): one `@Test` that later tickets extend, so the same
 * database and the same PG transactions carry through every phase.
 *
 * - Baseline: 10 normal orders. Each create is accepted as `PENDING`, the test plays that order's PG 알림 right after its
 *   confirmation, and the lookup finds `SUCCESS`.
 * - Fault: 10 other orders under response loss. The 3rd, 6th and 9th lose their create's response, while the PG holds their
 *   `SUCCESS` transactions. The test plays no 알림 for them, and nothing else looks them up. The other 7 finish like baseline orders.
 * - Diagnosis: three hypotheses for the 3 unresolved payments, written before the PG's list lookup that the test sends straight to
 *   the test PG. The lookup's status rejects "still processing", the order's log events reject "failed apply", and "lost response"
 *   is accepted. The lookups change no order, payment or product row.
 * - Recovery: the unresolved payments in the database are the targets, frozen once they equal the 3 lost orders. Each is
 *   recovered by the customer's reconcile endpoint as the owner, and the fault's same 10 orders are read again: all confirmed,
 *   with no second create. Repeating the reconcile and the 알림 changes nothing, and another user is refused.
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

        /** The PG contract's synthetic account, every PG request's `X-USER-ID`. The test PG's stubs match only this value. */
        private const val PG_USER_ID = "135135"

        /** The files the fault phase writes. Recovery hashes them before it starts and checks the hashes at the end. */
        private val FAULT_FILES = listOf("fault.json", "fault-health.json", "fault-prometheus.txt", "fault-log-events.json")
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
    fun `lost responses leave three payments unknown under health UP, a PG lookup tells why, reconcile recovers them once`() {
        val evidence = IncidentEvidence.start(objectMapper, Instant.now())
        val ownerRowsAtStart = ownerRows()
        var phase = "baseline"
        var failure: Throwable? = null
        try {
            val baseline = runBaseline(evidence)

            phase = "fault"
            val fault = runFault(evidence, baseline)

            phase = "diagnosis"
            runDiagnosis(evidence, fault)

            phase = "recovery"
            runRecovery(evidence, baseline, fault, ownerRowsAtStart)
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
     * The diagnosis of the fault's unresolved payments, as an operator would make it. Every [Hypothesis] and its expected observation
     * is fixed in the test and written to `hypotheses.json` before the first lookup. Then, for each lost order, the test sends the
     * PG's list lookup straight to the test PG by the PG order ID read from the payment row, and reads the order's captured log
     * events. Each hypothesis judges that observation, and `verdict.json` holds the hypotheses again with the judgements, so the
     * verdict reads on its own. Commerce is not asked: no endpoint of it is called, and the rows the lookups could have moved are
     * read before and after them and must be equal.
     *
     * The lookup lands in the same request log as commerce's own. The fault phase saw none for these PG order IDs, so exactly one
     * afterwards is the operator's, and commerce sent none in between. Every lookup finds the transaction `SUCCESS`, so "still
     * processing" falls to the lookup and "failed apply" can fall only to the event order.
     */
    private fun runDiagnosis(evidence: IncidentEvidence, fault: PhaseRecord) {
        val hypotheses = HypothesesRecord(
            writtenAt = Instant.now().toString(),
            hypotheses = Hypothesis.entries.map { HypothesisRecord(it, it.statement, it.expected) },
        )
        evidence.writeJson("hypotheses.json", hypotheses)
        val lost = fault.orders.filter { it.orderId in fault.lostOrderIds }
        val rowsBefore = orderRows(fault.lostOrderIds)

        val diagnoses = lost.map { order ->
            val pgOrderId = checkNotNull(order.pgOrderId) { "Order ${order.orderId} has no PG order ID." }
            val lookedUpAt = Instant.now().toString()
            val lookup = sendListLookupToPg(pgOrderId)
            check(lookup.statusCode() == 200) {
                "The PG's list lookup for $pgOrderId answered ${lookup.statusCode()}: ${lookup.body()}"
            }
            val observation = Observation(
                lookupStatuses = JsonPath.read(lookup.body(), "$[*].status"),
                events = orderApplicationLogs.logEventsOf(order.orderId).map { it.name },
            )

            OrderDiagnosis(
                orderId = order.orderId,
                pgOrderId = pgOrderId,
                lookedUpAt = lookedUpAt,
                lookupBody = lookup.body(),
                observed = observation,
                judgements = Hypothesis.entries.map { it.judge(observation) },
            )
        }
        val rowsAfter = orderRows(fault.lostOrderIds)
        evidence.writeJson(
            "verdict.json",
            VerdictRecord(
                hypothesesWrittenAt = hypotheses.writtenAt,
                hypotheses = hypotheses.hypotheses,
                orders = diagnoses,
            ),
        )

        assertThat(diagnoses).hasSize(LOST_PLACES.size).allSatisfy({ diagnosis ->
            assertThat(diagnosis.observed.lookupStatuses).containsExactly("SUCCESS")
            assertThat(diagnosis.verdictOf(Hypothesis.A)).isEqualTo(Verdict.REJECTED)
            assertThat(diagnosis.verdictOf(Hypothesis.B)).isEqualTo(Verdict.ACCEPTED)
            assertThat(diagnosis.verdictOf(Hypothesis.C)).isEqualTo(Verdict.REJECTED)
            assertThat(incidentPg.listLookupCount(diagnosis.pgOrderId)).isOne()
            assertThat(incidentPg.createCount(diagnosis.pgOrderId)).isOne()
        })
        assertThat(rowsBefore.mapValues { it.value.size }).isEqualTo(
            mapOf("orders" to LOST_PLACES.size, "payment" to LOST_PLACES.size, "product" to 1, "order_event_outbox" to 0),
        )
        assertThat(rowsAfter).isEqualTo(rowsBefore)
        assertThat(incidentPg.createCount()).isEqualTo(fault.pgCreateCount)
        assertThat(incidentPg.unmatchedRequests()).isEmpty()
    }

    /**
     * Recovers the fault's unresolved payments through the customer's reconcile endpoint and proves it on the fault's same 10
     * orders. The targets are read from the unresolved payments in the database, asserted equal to the lost orders, and frozen
     * before the first reconcile. Each target is reconciled once, as the owner, with response loss off: the reconcile looks the
     * payment up by its PG order ID and applies the `SUCCESS` it finds. Nothing sends a create.
     *
     * Before the first reconcile the fault's files are hashed, and `recovery.json` holds the hashes, which must still match at the
     * end: recovery does not rewrite the record of the incident.
     *
     * Then the repeats: a second reconcile and a second 알림 for every target, and another user's reconcile of one. A recovered
     * order is confirmed, so neither the reconcile nor the 알림 looks it up (`OrderPaymentSaga.checkLookupNeeded`): they reach no
     * TX B and leave no event, `result=unchanged` included. "Changes nothing" is therefore read as no new event, no PG request, and
     * the orders, payments, product and outbox rows equal to what they were.
     */
    private fun runRecovery(
        evidence: IncidentEvidence,
        baseline: PhaseRecord,
        fault: PhaseRecord,
        ownerRowsAtStart: Map<String, List<Map<String, Any?>>>,
    ) {
        val targets = unresolvedOrderIds().toList()
        assertThat(targets).isEqualTo(fault.lostOrderIds)
        assertThat(incidentPg.isResponseLossOn()).isFalse()
        val faultFileSha256 = FAULT_FILES.associateWith { evidence.sha256(it) }
        val createsBefore = incidentPg.createCount()

        targets.forEach { orderId ->
            val reconciled = assertThat(requestReconcile(orderId, owner.id)).hasStatusOk().bodyJson()
            reconciled.extractingPath("$.data.status").isEqualTo("CONFIRMED")
            reconciled.extractingPath("$.data.paymentStatus").isEqualTo("SUCCESS")
        }

        val record = observe(evidence, "recovery", fault.orderIds, targets, expectedUnknown = "0.0", faultFileSha256)

        val pgTransactions = incidentPg.transactions().associateBy { it.pgOrderId }
        assertThat(record.responseLossOn).isFalse()
        assertThat(record.lostOrderIds).isEqualTo(fault.lostOrderIds)
        assertThat(record.orders.map { it.orderId }).isEqualTo(fault.orderIds)
        assertThat(record.orders).hasSize(ORDERS_PER_PHASE).allSatisfy({
            val pgTransaction = pgTransactions.getValue(checkNotNull(it.pgOrderId))
            assertThat(it.orderStatus).isEqualTo("CONFIRMED")
            assertThat(it.paymentStatus).isEqualTo("SUCCESS")
            assertThat(it.reservedQuantity).isZero()
            assertThat(it.createCount).isOne()
            assertThat(pgTransaction.status.name).isEqualTo("SUCCESS")
            assertThat(it.transactionKey).isEqualTo(pgTransaction.transactionKey)
        })
        assertThat(record.orders.filter { it.orderId in targets }).allSatisfy({
            // One by the diagnosis, one by the reconcile.
            assertThat(it.listLookupCount).isEqualTo(2)
        })
        assertThat(record.unresolvedOrderIds).isEmpty()
        assertThat(record.unresolvedGauge).isEqualTo(mapOf("UNKNOWN" to "0.0", "PENDING" to "0.0"))
        assertThat(record.healthStatus).isEqualTo("UP")
        assertThat(record.pgTransactionCount).isEqualTo(2 * ORDERS_PER_PHASE)
        assertThat(record.pgCreateCount).isEqualTo(createsBefore)
        assertThat(record.productStock).isEqualTo(STOCK - 2 * ORDERS_PER_PHASE)
        assertThat(record.pgOrderIdsCreatedMoreThanOnce).isEmpty()
        assertThat(record.unmatchedPgRequests).isZero()
        assertThat(record.faultFileSha256).isEqualTo(faultFileSha256)
        targets.forEach { orderId ->
            val events = orderApplicationLogs.logEventsOf(orderId)
            assertThat(events.map { it.name }).containsExactly(
                "pg.create.outcome-unknown",
                "pg.lookup.found",
                "payment.apply.committed",
            )
            assertThat(events.drop(1)).allSatisfy({ assertThat(it.fields["trigger"]).isEqualTo("reconcile") })
            assertThat(events.last().fields).containsEntry("result", "changed").containsEntry("from", "UNKNOWN")
                .containsEntry("to", "SUCCESS")
        }

        val allOrderIds = baseline.orderIds + fault.orderIds
        val rowsBeforeRepeats = orderRows(allOrderIds)
        val eventsBeforeRepeats = orderApplicationLogs.logEvents()
        val pgRequestsBeforeRepeats = incidentPg.requestCount()
        targets.forEach { orderId ->
            val reconciled = assertThat(requestReconcile(orderId, owner.id)).hasStatusOk().bodyJson()
            reconciled.extractingPath("$.data.status").isEqualTo("CONFIRMED")
            assertThat(requestCallback(orderId)).hasStatusOk()
        }
        val otherUser = prepareUser()
        val refused = assertThat(requestReconcile(targets.first(), otherUser.id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        refused.extractingPath("$.meta.errorCode").isEqualTo("ORDER_NOT_FOUND")

        assertThat(orderRows(allOrderIds)).isEqualTo(rowsBeforeRepeats)
        assertThat(orderApplicationLogs.logEvents()).isEqualTo(eventsBeforeRepeats)
        assertThat(incidentPg.requestCount()).isEqualTo(pgRequestsBeforeRepeats)
        assertThat(outboxRowCounts()).isEqualTo(allOrderIds.associateWith { 1 })
        assertThat(ownerRows()).isEqualTo(ownerRowsAtStart)
        assertThat(FAULT_FILES.associateWith { evidence.sha256(it) }).isEqualTo(record.faultFileSha256)
    }

    /**
     * The rows that tell whether an order changed, read whole: the orders and payments of [orderIds], their outbox rows, and the
     * product, whose stock holds the orders' reservations.
     */
    private fun orderRows(orderIds: List<Long>): Map<String, List<Map<String, Any?>>> {
        val placeholders = orderIds.joinToString(",") { "?" }
        val ids = orderIds.toTypedArray()
        val outboxSql = "select * from order_event_outbox where order_id in ($placeholders) order by id"

        return mapOf(
            "orders" to jdbc.queryForList("select * from orders where id in ($placeholders) order by id", *ids),
            "payment" to jdbc.queryForList("select * from payment where order_id in ($placeholders) order by id", *ids),
            "product" to jdbc.queryForList("select * from product where id = ?", product.id),
            "order_event_outbox" to jdbc.queryForList(outboxSql, *ids),
        )
    }

    /** The owner's point account and coupons, read whole. No order of the incident may touch them. */
    private fun ownerRows(): Map<String, List<Map<String, Any?>>> {
        return mapOf(
            "point_account" to jdbc.queryForList("select * from point_account where user_id = ?", owner.id),
            "coupon" to jdbc.queryForList("select * from coupon where user_id = ? order by id", owner.id),
        )
    }

    /** The number of live outbox rows per order, for every order that has one. */
    private fun outboxRowCounts(): Map<Long, Int> {
        val sql = "select order_id, count(*) as row_count from order_event_outbox where deleted_at is null group by order_id"

        return jdbc.query(sql) { rs, _ -> rs.getLong("order_id") to rs.getInt("row_count") }.toMap()
    }

    /**
     * The PG's list lookup for [pgOrderId], sent over HTTP straight to the test PG with the PG contract's header, as an operator
     * with access to the PG would send it. Commerce is not in the path.
     */
    private fun sendListLookupToPg(pgOrderId: String): HttpResponse<String> {
        val query = URLEncoder.encode(pgOrderId, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("${server.baseUrl()}/api/v1/payments?orderId=$query"))
            .header("X-USER-ID", PG_USER_ID)
            .GET()
            .build()

        return HttpClient.newHttpClient().use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }
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
        faultFileSha256: Map<String, String>? = null,
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
            faultFileSha256 = faultFileSha256,
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

    /** The customer's reconcile of [orderId], sent as [userId]. */
    private fun requestReconcile(orderId: Long, userId: Long): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/payment/reconcile").header(UserIdHeader.NAME, userId).exchange()
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
    /**
     * Recovery's only: the SHA-256 of each fault file, taken before the first reconcile. Left out of the other phases' files, as
     * the shared mapper leaves out every `null`.
     */
    val faultFileSha256: Map<String, String>? = null,
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

/**
 * The three hypotheses for a payment left 결과 미확인 while the PG holds a successful transaction (spec 274, "가설과 진단"). Each is
 * written with its expected observation before any lookup, and [holds] is that expectation as a check on what was observed.
 */
private enum class Hypothesis(val statement: String, val expected: String) {
    A(
        statement = "The PG is still processing the payment.",
        expected = "The PG's list lookup finds the transaction PENDING.",
    ) {
        override fun holds(observation: Observation): Boolean {
            return observation.lookupStatuses == listOf("PENDING")
        }
    },
    B(
        statement = "The PG's success response was lost.",
        expected = "The lookup finds the transaction SUCCESS, and the order's only event is pg.create.outcome-unknown.",
    ) {
        override fun holds(observation: Observation): Boolean {
            return observation.lookupStatuses == listOf("SUCCESS") && observation.events == listOf("pg.create.outcome-unknown")
        }
    },
    C(
        statement = "Commerce received the success but failed to apply it.",
        expected = "The lookup finds the transaction SUCCESS, and the order's events hold pg.create.accepted, " +
            "then payment.apply.failed.",
    ) {
        override fun holds(observation: Observation): Boolean {
            val accepted = observation.events.indexOf("pg.create.accepted")
            val failedAfter = accepted >= 0 && "payment.apply.failed" in observation.events.drop(accepted + 1)

            return observation.lookupStatuses == listOf("SUCCESS") && failedAfter
        }
    },
    ;

    abstract fun holds(observation: Observation): Boolean

    fun judge(observation: Observation): Judgement {
        val verdict = if (holds(observation)) Verdict.ACCEPTED else Verdict.REJECTED

        return Judgement(hypothesis = this, expected = expected, verdict = verdict)
    }
}

private enum class Verdict { ACCEPTED, REJECTED }

/** What the diagnosis saw for one order: the statuses in the PG's answer to the list lookup, and the order's event names in order. */
private data class Observation(
    val lookupStatuses: List<String>,
    val events: List<String>,
)

/** One hypothesis judged against one order's observation, as `verdict.json` holds it. */
private data class Judgement(
    val hypothesis: Hypothesis,
    val expected: String,
    val verdict: Verdict,
)

/**
 * One lost order's diagnosis: when its lookup was sent, the PG's answer as received, what was observed, and each hypothesis's
 * judgement of that observation.
 */
private data class OrderDiagnosis(
    val orderId: Long,
    val pgOrderId: String,
    val lookedUpAt: String,
    val lookupBody: String,
    val observed: Observation,
    val judgements: List<Judgement>,
) {
    fun verdictOf(hypothesis: Hypothesis): Verdict {
        return judgements.single { it.hypothesis == hypothesis }.verdict
    }
}

private data class HypothesisRecord(
    val hypothesis: Hypothesis,
    val statement: String,
    val expected: String,
)

/** `hypotheses.json`: the hypotheses and their expected observations, written before the first lookup. */
private data class HypothesesRecord(
    val writtenAt: String,
    val hypotheses: List<HypothesisRecord>,
)

/** `verdict.json`: the hypotheses as written before the first lookup, and each lost order's judgements. */
private data class VerdictRecord(
    val hypothesesWrittenAt: String,
    val hypotheses: List<HypothesisRecord>,
    val orders: List<OrderDiagnosis>,
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

    /** The SHA-256 of the file [name] as it is now, in lowercase hex. Fails if the file does not exist. */
    fun sha256(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(name)))

        return HexFormat.of().formatHex(digest)
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
