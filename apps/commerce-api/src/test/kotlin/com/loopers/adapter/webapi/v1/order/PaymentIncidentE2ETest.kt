package com.loopers.adapter.webapi.v1.order

import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.annotation.JsonInclude
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.jayway.jsonpath.JsonPath
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.order.OrderEventOutboxModifyService
import com.loopers.application.order.provided.OrderEventRelayer
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.user.User
import com.loopers.support.CommerceBatchProcess
import com.loopers.support.CommerceStreamerProcess
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.EnableTestPg
import com.loopers.support.IncidentPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.WeeklyRankingBatchTables
import com.loopers.support.logEvents
import com.loopers.support.logEventsOf
import com.loopers.support.test.BaseRealPortWebApiAdapterTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.core.env.Environment
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.context.TestPropertySource
import org.springframework.test.util.AopTestUtils
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource

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
 * - Follow-on (spec 286): the relay sends the 20 orders' outbox rows to the class's own topic, one of them twice, and the real
 *   commerce-streamer records one purchase activity per order. The real commerce-batch then publishes revision 1 of the week,
 *   and the ranking API, `NOT_PUBLISHED` before the run, shows the product at score 20: the baseline's 10, the fault's 7 and
 *   the 3 recovered. The phase ends with the same observation as the others and reads again what recovery proved.
 *
 * The clock is pinned from the start to one finished week ([PERIOD_START]): the baseline and the fault confirm on its Tuesday, and
 * recovery reconciles on its Thursday, so the run cannot split across a Monday and the two times cannot be mistaken for each other.
 * Evidence times (`observedAt` and the like) stay on the real clock. The class names its own topic and consumer group, so no other
 * class's records reach its streamer.
 *
 * Each phase reads its record fresh from the database, the test PG's request log, and one scrape of the management port, writes it
 * to the run's evidence directory ([IncidentEvidence]), and asserts on that record. Health and Prometheus are written as fetched.
 * No status is written into the commerce database: orders move only through [mvc]. Every identifier asserted or written comes from
 * commerce or the PG: order IDs as the order creator returned the saved orders, the rest read back from the database or the PG.
 *
 * A failure anywhere in the phases writes `failure.json` and deletes nothing. The `finally` also releases response loss and checks
 * that no PG order ID received a second create. A failure before the run directory exists (`@BeforeEach`) has nowhere to write.
 *
 * Confirmations commit per request, so the class leaves the test transaction and empties the tables itself
 * (`UnresolvedPaymentGaugeE2ETest`'s pattern). The gauge is a number in memory that only this class's confirmations and reconciles
 * move in its context, and the fault's orders are all resolved within the one test, so the phases assert its absolute values.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=${PaymentIncidentE2ETest.TOPIC}"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentIncidentE2ETest(
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: TestClock,
    private val orderEventRelayer: OrderEventRelayer,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
    private val environment: Environment,
    private val dataSource: DataSource,
) : BaseRealPortWebApiAdapterTest() {
    companion object {
        const val TOPIC = "order-events-payment-incident"

        private const val GROUP = "commerce-streamer-payment-incident"

        /** The ranking period the incident falls in: the UTC week from Monday 2026-09-07T00:00Z. */
        private val PERIOD_START: LocalDate = LocalDate.parse("2026-09-07")

        /** The one revision the follow-on publishes for [PERIOD_START]. There is no revision 2. */
        private const val SNAPSHOT_ID = "2026-09-07-r1"

        private const val REVISION = 1L

        private const val RANKING_ENDPOINT = "/api/v1/rankings/weekly"

        /** Where the clock stands while the baseline and the fault confirm: the Tuesday of [PERIOD_START]'s week. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-09-08T10:00:00Z")

        /** Where the clock stands while recovery reconciles: two days later, the Thursday of the same week. */
        private val RECOVERED_AT: Instant = Instant.parse("2026-09-10T10:00:00Z")

        /**
         * Where the clock stands while recovery repeats its reconciles and 알림: a second after [RECOVERED_AT], so a repeat that
         * confirmed an order again would leave a time of its own.
         */
        private val REPEATED_AT: Instant = RECOVERED_AT.plusSeconds(1)

        /** Where the follow-on's relay round starts: recovery plus 5 s, when every one of the 20 rows is due. */
        private val RELAYED_AT: Instant = RECOVERED_AT.plusSeconds(5)

        /** A second past the 60 s lease the stopped round left on its last row, as in `OutboxDeliveryE2ETest`. */
        private val PAST_THE_LEASE: Instant = RELAYED_AT.plusSeconds(61)

        /** How MySQL formats a stored time as UTC. A JDBC read of the column would shift it into the test JVM's zone. */
        private const val STORED_UTC = "%Y-%m-%dT%H:%i:%s.%fZ"

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

        private val GAUGE_STATUSES = listOf("UNKNOWN", "PENDING")

        /** The files the fault phase writes. Recovery hashes them before it starts and checks the hashes at the end. */
        private val FAULT_FILES = phaseFiles("fault") + "fault-log-events.json"

        /** The three files [observe] writes for [phase]: the record, health as fetched, and Prometheus as fetched. */
        private fun phaseFiles(phase: String): List<String> {
            return listOf("$phase.json", "$phase-health.json", "$phase-prometheus.txt")
        }
    }

    /** Field injection, not the constructor. The reason is on the same field in `OrderPaymentIntegrationTest`. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val incidentPg by lazy { IncidentPg(server) }

    /** Where the follow-on stops its relay round (`stopRelayAfterBrokerAckBeforeMarkingPublished`). Unstubbed, it is the real service. */
    @MockkSpyBean
    private lateinit var orderEventOutboxModifyService: OrderEventOutboxModifyService

    private lateinit var owner: User

    /** The streamer the follow-on started, kept so that [cleanUp] ends it when the phase did not. */
    private var streamer: CommerceStreamerProcess? = null

    /** The saga's log events, from the order application package's logger. */
    private val orderApplicationLogs = ListAppender<ILoggingEvent>()

    private val orderApplicationLogger = LoggerFactory.getLogger("com.loopers.application.order") as Logger

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        // The ranking's and Batch's tables as the batch creates them, whatever an earlier class left (`WeeklyRankingFlowTest`).
        WeeklyRankingBatchTables.reset(dataSource)
        owner = prepareUser()
        charge(amount = POINT_BALANCE, user = owner)
        prepareCoupon(prepareCouponCampaign(), owner)
        prepareProduct(price = PRICE, stock = STOCK)
        orderApplicationLogs.start()
        orderApplicationLogger.addAppender(orderApplicationLogs)
    }

    /**
     * Ends the streamer before the tables are emptied, so no consumer is reading while they empty, and when the test failed too.
     * Both are in one method because JUnit doesn't order a class's `@AfterEach` methods.
     */
    @AfterEach
    fun cleanUp() {
        try {
            streamer?.terminate()
            streamer = null
        } finally {
            orderApplicationLogger.detachAppender(orderApplicationLogs)
            databaseCleanUp.truncateAllTables()
        }
    }

    // The name is spec 286's, one sentence per phase, and no shorter sentence says all five; issue 288 keeps it whole.
    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun `lost responses leave three payments unknown under health UP, a PG lookup tells why, reconcile recovers them once, and the weekly ranking counts all twenty`() {
        val evidence = IncidentEvidence.start(objectMapper, Instant.now())
        var phase = "baseline"
        var failure: Throwable? = null
        try {
            val ownerRowsAtStart = ownerRows()
            clock.pin(CONFIRMED_AT)
            val baseline = runBaseline(evidence)

            phase = "fault"
            val fault = runFault(evidence, baseline)

            phase = "diagnosis"
            runDiagnosis(evidence, fault)

            phase = "recovery"
            clock.pin(RECOVERED_AT)
            runRecovery(evidence, baseline, fault, ownerRowsAtStart)

            phase = "follow-on"
            runFollowOn(evidence, baseline, fault, ownerRowsAtStart)
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
        val orderIds = createOrders(ORDERS_PER_PHASE)
        orderIds.forEach { orderId ->
            incidentPg.registerNormal(nextPgOrderId(), amount = PRICE, card = CARD)
            // Pending, not unknown: both answer 202 and the 알림 finishes either, so only the status tells the PG accepted the create.
            val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED).bodyJson()
            body.extractingPath("$.data.paymentStatus").isEqualTo("PENDING")
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
        val orderIds = createOrders(ORDERS_PER_PHASE)
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
            val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED).bodyJson()
            if (orderId in lostOrderIds) {
                body.extractingPath("$.data.paymentStatus").isEqualTo("UNKNOWN")
            } else {
                body.extractingPath("$.data.paymentStatus").isEqualTo("PENDING")
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
        val faultFileSha256 = faultFileSha256(evidence)
        val createsBefore = incidentPg.createCount()

        targets.forEach { orderId ->
            val body = assertThat(requestReconcile(orderId, owner.id)).hasStatusOk().bodyJson()
            body.extractingPath("$.data.status").isEqualTo("CONFIRMED")
            body.extractingPath("$.data.paymentStatus").isEqualTo("SUCCESS")
        }

        val record = observe(
            evidence,
            "recovery",
            fault.orderIds,
            targets,
            expectedUnknown = "0.0",
            faultFileSha256 = faultFileSha256,
        )

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
        clock.pin(REPEATED_AT)
        val rowsBeforeRepeats = orderRows(allOrderIds)
        val eventsBeforeRepeats = orderApplicationLogs.logEvents()
        val pgRequestsBeforeRepeats = incidentPg.requestCount()
        targets.forEach { orderId ->
            val secondReconcileBody = assertThat(requestReconcile(orderId, owner.id)).hasStatusOk().bodyJson()
            secondReconcileBody.extractingPath("$.data.status").isEqualTo("CONFIRMED")
            assertThat(requestCallback(orderId)).hasStatusOk()
        }
        val otherUser = prepareUser()
        val error = assertThat(requestReconcile(targets.first(), otherUser.id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("ORDER_NOT_FOUND")

        assertThat(orderRows(allOrderIds)).isEqualTo(rowsBeforeRepeats)
        assertThat(orderApplicationLogs.logEvents()).isEqualTo(eventsBeforeRepeats)
        assertThat(incidentPg.requestCount()).isEqualTo(pgRequestsBeforeRepeats)
        assertThat(outboxRowCounts()).isEqualTo(allOrderIds.associateWith { 1 })
        assertThat(ownerRows()).isEqualTo(ownerRowsAtStart)
        assertThat(readOrders(allOrderIds)).hasSize(2 * ORDERS_PER_PHASE).allSatisfy({
            assertThat(it.createCount).isOne()
        })
        val writtenSha256: Map<String, String> = JsonPath.read(evidence.read("recovery.json"), "$.faultFileSha256")
        assertThat(faultFileSha256(evidence)).isEqualTo(writtenSha256)
    }

    /**
     * Carries the 20 orders past recovery to the purchase activities: the real relay sends their outbox rows to the class's topic,
     * and the real commerce-streamer, started here and ended in this phase's `finally`, records them. The test drives the relay
     * ([OrderEventRelayer]) by hand; `order-events.relay.enabled` stays off, so nothing sent a row before this phase.
     *
     * One round at [RELAYED_AT] claims all 20 rows, oldest due first: the 17 confirmed at [CONFIRMED_AT] by row ID, then the 3
     * recovered at [RECOVERED_AT]. The round stops after the broker's ack of the last of them, the third recovered order, and before
     * its `PUBLISHED` is saved, so that row alone stays pending under its lease. The next round, past the lease, sends it again.
     * The broker then holds 21 records of 20 events, and the streamer records the repeat as already recorded, so the purchase
     * activities stay at 20: the records on the topic and the purchase activities are counted apart.
     *
     * Then the week's ranking: the API is read before the run, commerce-batch runs `weeklyRanking` once from its bootJar for
     * revision 1 of [PERIOD_START], and the API is read again. Each answer's body is written as received.
     *
     * The phase ends with [observe], as the others do, and its record carries [FollowOnRecord]: per order, the outbox row and the
     * purchase activities, then the snapshot rows and the four counts kept apart. The assertions read that record and the bodies
     * written. The purchase activities' count comes first and the ranking right after it, so a row the relay skips fails on 19
     * and a recovery in another week fails on the score, before any check of a single row. Last, the record reads again what
     * recovery proved: the stock, one create per PG order ID, the PG transactions, the owner's rows and the fault files' hashes.
     *
     * Every row is read with `select`. Past `setUp`'s reset, the test writes nothing to the streamer's or the ranking's tables.
     */
    private fun runFollowOn(
        evidence: IncidentEvidence,
        baseline: PhaseRecord,
        fault: PhaseRecord,
        ownerRowsAtStart: Map<String, List<Map<String, Any?>>>,
    ) {
        val allOrderIds = baseline.orderIds + fault.orderIds
        val recoveredOrderIds = fault.lostOrderIds
        val republishedOrderId = recoveredOrderIds.last()
        val startedStreamer = startTheStreamer()
        try {
            TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic).use { orderEvents ->
                clock.pin(RELAYED_AT)
                orderEventOutboxModifyService.stopRelayAfterBrokerAckBeforeMarkingPublishedOf(outboxIdOf(republishedOrderId))
                assertThrows<DataAccessResourceFailureException> { orderEventRelayer.publishDueEvents() }
                val stoppedRoundRecords = orderEvents.publishedRecords(expected = allOrderIds.size)
                val outboxesAfterTheStop = outboxRows()

                clock.pin(PAST_THE_LEASE)
                orderEventRelayer.publishDueEvents()
                val nextRoundRecords = orderEvents.publishedRecords(expected = 1)

                val records = stoppedRoundRecords + nextRoundRecords
                val lines = startedStreamer.awaitRecordedLines(expected = records.size) { followOnRowsLine() }
                val confirmations = processedConfirmationRows()
                val confirmedAts = orderConfirmedAts(allOrderIds)

                val rankingBeforeRun = requestGetWeeklyRanking()
                assertThat(rankingBeforeRun).hasStatusOk()
                evidence.writeText("follow-on-ranking-before-run.json", bodyAsReceived(rankingBeforeRun))
                CommerceBatchProcess.run(
                    CommerceBatchProcess.Settings.of(environment),
                    CommerceBatchProcess.WeeklyRankingParameters(PERIOD_START, SNAPSHOT_ID, REVISION),
                )
                val rankingAfterRun = requestGetWeeklyRanking()
                assertThat(rankingAfterRun).hasStatusOk()
                evidence.writeText("follow-on-ranking.json", bodyAsReceived(rankingAfterRun))

                val outboxRows = outboxRows()
                val outboxes = outboxRows.associateBy { it.orderId }
                val purchaseActivityRows = purchaseActivityRows()
                val readFollowOn = FollowOnRecord(
                    orders = allOrderIds.sorted().map { orderId ->
                        val outbox = outboxes[orderId]
                        FollowOnOrderRecord(
                            orderId = orderId,
                            eventId = outbox?.eventId,
                            outboxStatus = outbox?.status,
                            outboxAttemptCount = outbox?.attemptCount,
                            purchaseActivities = purchaseActivityRows.filter { it.orderId == orderId },
                        )
                    },
                    snapshots = snapshotRows(),
                    ownerRows = ownerRows(),
                    counts = FollowOnCounts(
                        topicRecords = records.size,
                        outboxRows = outboxRows.size,
                        purchaseActivities = purchaseActivityRows.size,
                        processedConfirmations = confirmations.size,
                    ),
                )
                val record = observe(
                    evidence,
                    "follow-on",
                    allOrderIds,
                    recoveredOrderIds,
                    expectedUnknown = "0.0",
                    faultFileSha256 = faultFileSha256(evidence),
                    followOn = readFollowOn,
                )
                val written = checkNotNull(record.followOn)
                val purchaseActivities = written.orders.flatMap { it.purchaseActivities }

                assertThat(purchaseActivities).describedAs { followOnRowsLine() }.hasSize(allOrderIds.size)

                val writtenRankingBeforeRun = evidence.read("follow-on-ranking-before-run.json")
                assertThat(JsonPath.read<String>(writtenRankingBeforeRun, "$.data.status")).isEqualTo("NOT_PUBLISHED")
                assertThat(JsonPath.read<Any?>(writtenRankingBeforeRun, "$.data.snapshotId")).isNull()
                assertThat(rankingItemsOf(writtenRankingBeforeRun)).isEmpty()
                val writtenRanking = evidence.read("follow-on-ranking.json")
                assertThat(JsonPath.read<String>(writtenRanking, "$.data.status")).isEqualTo("PUBLISHED")
                assertThat(JsonPath.read<String>(writtenRanking, "$.data.snapshotId")).isEqualTo(SNAPSHOT_ID)
                assertThat(rankingItemsOf(writtenRanking)).containsExactly("rank 1 product ${product.id} score 20")
                assertThat(written.snapshots.map { it.describe() }).containsExactly(
                    "$SNAPSHOT_ID period $PERIOD_START revision $REVISION state FROZEN inputs 20",
                )
                assertThat(written.counts).isEqualTo(
                    FollowOnCounts(topicRecords = 21, outboxRows = 20, purchaseActivities = 20, processedConfirmations = 20),
                )

                val expectedConfirmedAt = allOrderIds.associateWith { orderId ->
                    if (orderId in recoveredOrderIds) RECOVERED_AT else CONFIRMED_AT
                }
                val periodEnd = PERIOD_START.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant()
                assertThat(written.orders.map { it.purchaseActivities.size }).containsOnly(1)
                assertThat(purchaseActivities.map { it.orderId }).containsExactlyElementsOf(allOrderIds.sorted())
                assertThat(purchaseActivities).allSatisfy({
                    assertThat(it.productId).isEqualTo(product.id)
                    assertThat(it.quantity).isOne()
                    assertThat(it.confirmedAt).isEqualTo(expectedConfirmedAt.getValue(it.orderId))
                    assertThat(it.confirmedAt).isAfterOrEqualTo(PERIOD_START.atStartOfDay(ZoneOffset.UTC).toInstant())
                        .isBefore(periodEnd)
                })
                assertThat(purchaseActivities.count { it.orderId in baseline.orderIds }).isEqualTo(ORDERS_PER_PHASE)
                assertThat(purchaseActivities.count { it.orderId in fault.orderIds }).isEqualTo(ORDERS_PER_PHASE)
                recoveredOrderIds.forEach { orderId ->
                    assertThat(purchaseActivities.single { it.orderId == orderId }).isEqualTo(
                        PurchaseActivityRow(orderId, product.id, quantity = 1, confirmedAt = RECOVERED_AT),
                    )
                }
                assertThat(confirmations.map { it.orderId }).containsExactlyElementsOf(allOrderIds.sorted())
                assertThat(confirmations).allSatisfy({
                    assertThat(it.eventId).isEqualTo(eventIdOf(it.orderId))
                    assertThat(it.confirmedAt).isEqualTo(expectedConfirmedAt.getValue(it.orderId))
                })
                assertThat(confirmedAts).isEqualTo(expectedConfirmedAt)

                assertThat(outboxesAfterTheStop.filter { it.status == "PENDING" }).containsExactly(
                    OutboxRow(republishedOrderId, eventIdOf(republishedOrderId), status = "PENDING", attemptCount = 1),
                )
                assertThat(outboxesAfterTheStop.filter { it.status == "PUBLISHED" }).hasSize(allOrderIds.size - 1)
                assertThat(outboxRowCounts()).isEqualTo(allOrderIds.associateWith { 1 })
                assertThat(written.orders).hasSize(allOrderIds.size).allSatisfy({
                    assertThat(it.eventId).isEqualTo(eventIdOf(it.orderId))
                    assertThat(it.outboxStatus).isEqualTo("PUBLISHED")
                    assertThat(it.outboxAttemptCount).isEqualTo(if (it.orderId == republishedOrderId) 2 else 1)
                })

                val recordEventIds = records.map { eventIdOfRecord(it.value()) }
                assertThat(stoppedRoundRecords.map { eventIdOfRecord(it.value()) })
                    .containsExactlyInAnyOrderElementsOf(allOrderIds.map(::eventIdOf))
                assertThat(nextRoundRecords.map { eventIdOfRecord(it.value()) }).containsExactly(eventIdOf(republishedOrderId))
                assertThat(records).hasSize(allOrderIds.size + 1)
                assertThat(recordEventIds.distinct()).hasSize(allOrderIds.size)
                assertThat(recordEventIds.groupingBy { it }.eachCount().filterValues { it > 1 })
                    .isEqualTo(mapOf(eventIdOf(republishedOrderId) to 2))

                assertThat(lines).hasSize(records.size)
                allOrderIds.forEach { orderId ->
                    val expectedResults = if (orderId == republishedOrderId) {
                        listOf("RECORDED", "ALREADY_RECORDED")
                    } else {
                        listOf("RECORDED")
                    }
                    assertThat(resultsOf(lines, orderId)).describedAs("order $orderId").isEqualTo(expectedResults)
                }

                val pgTransactions = incidentPg.transactions().associateBy { it.pgOrderId }
                assertThat(record.orders.map { it.orderId }).isEqualTo(allOrderIds)
                assertThat(record.orders).hasSize(2 * ORDERS_PER_PHASE).allSatisfy({
                    assertThat(it.orderStatus).isEqualTo("CONFIRMED")
                    assertThat(it.paymentStatus).isEqualTo("SUCCESS")
                    assertThat(it.reservedQuantity).isZero()
                    assertThat(it.createCount).isOne()
                    assertThat(it.transactionKey).isEqualTo(pgTransactions.getValue(checkNotNull(it.pgOrderId)).transactionKey)
                })
                assertThat(record.unresolvedOrderIds).isEmpty()
                assertThat(record.unresolvedGauge).isEqualTo(mapOf("UNKNOWN" to "0.0", "PENDING" to "0.0"))
                assertThat(record.healthStatus).isEqualTo("UP")
                assertThat(record.pgTransactionCount).isEqualTo(2 * ORDERS_PER_PHASE)
                assertThat(record.pgCreateCount).isEqualTo(2 * ORDERS_PER_PHASE)
                assertThat(record.productStock).isZero()
                assertThat(record.pgOrderIdsCreatedMoreThanOnce).isEmpty()
                assertThat(record.unmatchedPgRequests).isZero()
                assertThat(written.ownerRows).isEqualTo(ownerRowsAtStart)
                val recoverySha256: Map<String, String> = JsonPath.read(evidence.read("recovery.json"), "$.faultFileSha256")
                assertThat(record.faultFileSha256).isEqualTo(recoverySha256)
                assertThat(faultFileSha256(evidence)).isEqualTo(recoverySha256)
            }
        } finally {
            startedStreamer.terminate()
            streamer = null
        }
    }

    /**
     * `stopRelayAfterBrokerAckBeforeMarkingPublished` at one row: the first `markPublished` of [orderEventOutboxId] throws, and
     * every other call is the real one. The round has saved the results before it but none after it, so only the row a round marks
     * last is left alone `PENDING` under its lease, sent once and due to be sent again. The shared stub stops at the first row
     * marked, which here would leave all 20 sent and unmarked.
     */
    private fun OrderEventOutboxModifyService.stopRelayAfterBrokerAckBeforeMarkingPublishedOf(orderEventOutboxId: Long) {
        val target = AopTestUtils.getUltimateTargetObject<OrderEventOutboxModifyService>(this)
        val failure = DataAccessResourceFailureException("The relay stopped after the broker's ack, before saving PUBLISHED.")
        every { target.markPublished(orderEventOutboxId) } throws failure andThenAnswer { callOriginal() }
    }

    /** Starts the streamer on the context's MySQL and broker, with the class's topic and group, and keeps it for [cleanUp]. */
    private fun startTheStreamer(): CommerceStreamerProcess {
        val settings = CommerceStreamerProcess.Settings.of(environment, kafkaProperties, orderEventsProperties, GROUP)

        return CommerceStreamerProcess.start(settings).also { streamer = it }
    }

    private fun eventIdOf(orderId: Long): String {
        return "order-confirmed:v1:$orderId"
    }

    /** The event ID a record on the topic carries in its value. */
    private fun eventIdOfRecord(value: String): String {
        return JsonPath.read(value, "$.eventId")
    }

    /** The `result=` of each delivery of [orderId]'s event among the streamer's [lines], in the order it logged them. */
    private fun resultsOf(lines: List<String>, orderId: Long): List<String> {
        return lines.filter { "eventId=${eventIdOf(orderId)}," in it }.map { it.substringAfter("result=") }
    }

    private fun outboxIdOf(orderId: Long): Long {
        return jdbc.queryForObject("select id from order_event_outbox where order_id = ?", Long::class.javaObjectType, orderId)!!
    }

    /** Every live outbox row, in order ID order. */
    private fun outboxRows(): List<OutboxRow> {
        val sql = "select order_id, event_id, status, attempt_count from order_event_outbox " +
            "where deleted_at is null order by order_id"

        return jdbc.query(sql) { rs, _ ->
            OutboxRow(
                orderId = rs.getLong("order_id"),
                eventId = rs.getString("event_id"),
                status = rs.getString("status"),
                attemptCount = rs.getInt("attempt_count"),
            )
        }
    }

    /** The purchase activities the streamer committed, in order ID order. Only the streamer writes the table. */
    private fun purchaseActivityRows(): List<PurchaseActivityRow> {
        val sql = "select order_id, product_id, quantity, date_format(confirmed_at, '$STORED_UTC') as confirmed_at " +
            "from purchase_activity order by order_id, product_id"

        return jdbc.query(sql) { rs, _ ->
            PurchaseActivityRow(
                orderId = rs.getLong("order_id"),
                productId = rs.getLong("product_id"),
                quantity = rs.getInt("quantity"),
                confirmedAt = Instant.parse(rs.getString("confirmed_at")),
            )
        }
    }

    /** The order confirmations the streamer marked processed, in order ID order. Only the streamer writes the table. */
    private fun processedConfirmationRows(): List<ProcessedConfirmationRow> {
        val sql = "select event_id, order_id, date_format(confirmed_at, '$STORED_UTC') as confirmed_at " +
            "from processed_order_confirmation order by order_id"

        return jdbc.query(sql) { rs, _ ->
            ProcessedConfirmationRow(
                eventId = rs.getString("event_id"),
                orderId = rs.getLong("order_id"),
                confirmedAt = Instant.parse(rs.getString("confirmed_at")),
            )
        }
    }

    /** Every snapshot the batch left, in revision order. Only the batch writes the table. */
    private fun snapshotRows(): List<SnapshotRow> {
        val sql = "select snapshot_id, period_start, revision, state, date_format(frozen_at, '$STORED_UTC') as frozen_at, " +
            "input_count, input_checksum from weekly_ranking_snapshot order by revision"

        return jdbc.query(sql) { rs, _ ->
            SnapshotRow(
                snapshotId = rs.getString("snapshot_id"),
                periodStart = rs.getString("period_start"),
                revision = rs.getLong("revision"),
                state = rs.getString("state"),
                frozenAt = rs.getString("frozen_at"),
                inputCount = rs.getLong("input_count"),
                inputChecksum = rs.getString("input_checksum"),
            )
        }
    }

    /** The customer's weekly ranking of [PERIOD_START]. */
    private fun requestGetWeeklyRanking(): MvcTestResult {
        return mvc.get().uri(RANKING_ENDPOINT).param("periodStart", PERIOD_START.toString()).exchange()
    }

    /** The response's body as the API sent it: the API writes UTF-8 JSON, whatever charset the response names. */
    private fun bodyAsReceived(result: MvcTestResult): String {
        return result.response.getContentAsString(StandardCharsets.UTF_8)
    }

    /** The ranking's items in a written body, one line each, in the body's order. */
    private fun rankingItemsOf(body: String): List<String> {
        return objectMapper.readTree(body)["data"]["items"].asIterable().map { item ->
            "rank ${item["rank"].asInt()} product ${item["product"]["id"].asLong()} score ${item["score"].asLong()}"
        }
    }

    /** Each order's `confirmed_at` as stored, by order ID. */
    private fun orderConfirmedAts(orderIds: List<Long>): Map<Long, Instant> {
        val placeholders = orderIds.joinToString(",") { "?" }
        val sql = "select id, date_format(confirmed_at, '$STORED_UTC') as confirmed_at from orders where id in ($placeholders)"

        val mapper = RowMapper { rs, _ -> rs.getLong("id") to Instant.parse(rs.getString("confirmed_at")) }

        return jdbc.query(sql, mapper, *orderIds.toTypedArray()).toMap()
    }

    /** The rows a wait prints when it runs out: the outbox rows and the streamer's two tables. */
    private fun followOnRowsLine(): String {
        return "outbox rows ${outboxRows()}; processed_order_confirmation ${processedConfirmationRows()}; " +
            "purchase_activity ${purchaseActivityRows()}"
    }

    /** The SHA-256 of each fault file as it is now, by file name. */
    private fun faultFileSha256(evidence: IncidentEvidence): Map<String, String> {
        return FAULT_FILES.associateWith { evidence.sha256(it) }
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
            .header("X-USER-ID", TestPg.PG_USER_ID)
            .GET()
            .build()

        return HttpClient.newHttpClient().use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }
    }

    /**
     * Reads the phase's record and writes `<phase>.json`, `<phase>-health.json` and `<phase>-prometheus.txt`. One scrape must read
     * the gauge's `UNKNOWN` series as [expectedUnknown] and `PENDING` as `0.0`: the gauge moves before a response returns, so there
     * is nothing to wait for. The files hold that scrape, the one the record's gauge and health come from.
     */
    private fun observe(
        evidence: IncidentEvidence,
        phase: String,
        orderIds: List<Long>,
        lostOrderIds: List<Long>,
        expectedUnknown: String,
        faultFileSha256: Map<String, String>? = null,
        followOn: FollowOnRecord? = null,
    ): PhaseRecord {
        val scraped = scrape()
        assertThat(scraped.gauge).isEqualTo(mapOf("UNKNOWN" to expectedUnknown, "PENDING" to "0.0"))
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
            followOn = followOn,
        )
        val (recordFile, healthFile, prometheusFile) = phaseFiles(phase)
        evidence.writeJson(recordFile, record)
        evidence.writeText(healthFile, scraped.health)
        evidence.writeText(prometheusFile, scraped.prometheus)

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
     * Creates [count] orders of one unit of the product for the owner, and returns their IDs in creation order. The order creator
     * returns the saved order, so each ID is the database's own.
     */
    private fun createOrders(count: Int): List<Long> {
        return List(count) { prepareOrder(owner, listOf(product), quantity = 1).id }
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
        val json = """{"transactionKey":"$transactionKey","orderId":"$pgOrderId","status":"${transaction.status}"}"""

        return mvc.post().uri("/api/v1/payments/callback")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
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
     * Recovery's and the follow-on's: the SHA-256 of each fault file, taken before the first reconcile and again at the end. Left
     * out of the other phases' files, as the shared mapper leaves out every `null`.
     */
    val faultFileSha256: Map<String, String>? = null,
    /** The follow-on's only: what the relay, the streamer and the batch left. Left out of the other phases' files. */
    val followOn: FollowOnRecord? = null,
)

/** What the follow-on read after the relay, the streamer and the batch, as `follow-on.json` holds it. */
private data class FollowOnRecord(
    val orders: List<FollowOnOrderRecord>,
    val snapshots: List<SnapshotRow>,
    /** The owner's point account and coupons, read whole at the end, to equal the run's first reading. */
    val ownerRows: Map<String, List<Map<String, Any?>>>,
    val counts: FollowOnCounts,
)

/**
 * One order past recovery: its outbox row and the purchase activities the streamer recorded for it. Every field is written,
 * `null` included, so an order without an outbox row says so.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
private data class FollowOnOrderRecord(
    val orderId: Long,
    val eventId: String?,
    val outboxStatus: String?,
    val outboxAttemptCount: Int?,
    val purchaseActivities: List<PurchaseActivityRow>,
)

/** The four counts the follow-on keeps apart: a publish retry is a record on the topic, not a purchase. */
private data class FollowOnCounts(
    val topicRecords: Int,
    val outboxRows: Int,
    val purchaseActivities: Int,
    val processedConfirmations: Int,
)

/** One row of commerce-batch's `weekly_ranking_snapshot`. */
private data class SnapshotRow(
    val snapshotId: String,
    val periodStart: String,
    val revision: Long,
    val state: String,
    val frozenAt: String,
    val inputCount: Long,
    val inputChecksum: String,
) {
    fun describe(): String {
        return "$snapshotId period $periodStart revision $revision state $state inputs $inputCount"
    }
}

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

/** One outbox row as the follow-on reads it. */
private data class OutboxRow(
    val orderId: Long,
    val eventId: String,
    val status: String,
    val attemptCount: Int,
)

/** One row of commerce-streamer's `purchase_activity`. */
private data class PurchaseActivityRow(
    val orderId: Long,
    val productId: Long,
    val quantity: Int,
    val confirmedAt: Instant,
)

/** One row of commerce-streamer's `processed_order_confirmation`. */
private data class ProcessedConfirmationRow(
    val eventId: String,
    val orderId: Long,
    val confirmedAt: Instant,
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

    /** The text of the file [name], as a later check reads back what was written. Fails if the file does not exist. */
    fun read(name: String): String {
        return Files.readString(directory.resolve(name))
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
