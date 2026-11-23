package com.loopers.adapter.webapi.v1.ranking

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.order.OrderEventOutboxModifyService
import com.loopers.application.order.OrderEventRelay
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.product.Product
import com.loopers.domain.user.User
import com.loopers.support.CommerceBatchProcess
import com.loopers.support.CommerceStreamerProcess
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.WeeklyRankingBatchTables
import com.loopers.support.stopRelayAfterBrokerAckBeforeMarkingPublished
import com.loopers.support.test.BaseWebApiAdapterTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.core.env.Environment
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.wiremock.spring.InjectWireMock
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

/**
 * One order confirmation to the weekly ranking's response, through the three apps on one database (spec 259, the assignment's §7):
 * an order and its confirmation over HTTP with the test PG, the outbox relay, the real broker, the real commerce-streamer recording
 * the purchase activity, the real commerce-batch running `weeklyRanking`, and the ranking read over HTTP. Only the relay rounds and
 * the batch runs are driven by hand. The streamer ([CommerceStreamerProcess]) and the batch ([CommerceBatchProcess]) are child
 * processes of their bootJars at HEAD.
 *
 * The ranking's tables are the batch's own: the scene drops them and runs the batch's `weekly-ranking-schema.sql` before anything
 * else ([WeeklyRankingBatchTables]), since the `NOT_PUBLISHED` read comes before the first run. The contract-only copy is never
 * applied. `purchase_activity` is the table the streamer's start creates, and only the streamer writes it.
 *
 * The 늦은 구매 is late for a real reason: the fourth order is confirmed inside the ranking period, but its outbox row is relayed
 * only after revision 1 froze its snapshot. One of the first three events is published twice, by a relay round stopped between the
 * broker's ack and saving `PUBLISHED`. That round claims only the second order's row, so only that event is published again.
 *
 * Confirmation times are pinned by [TestClock]. The batch child runs on the real clock, so `inputFrozenAt` is compared between
 * the two revisions and never asserted as a literal. Batch's metadata is not asserted; commerce-batch's tests read it.
 *
 * Requests commit one by one and the child processes read what committed, so the class leaves the test transaction and cleans
 * with [DatabaseCleanUp] (`OrderConfirmationApiTest`'s pattern). The class names its own topic and group. The scene is one
 * `@Test`, and it logs what it observed in four INFO lines, for `scripts/scene-lines`.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=${WeeklyRankingFlowTest.TOPIC}"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WeeklyRankingFlowTest(
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
    private val orderEventPublisher: OrderEventPublisher,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
    private val environment: Environment,
) : BaseWebApiAdapterTest() {
    companion object {
        const val TOPIC = "order-events-weekly-ranking-flow"

        private const val GROUP = "commerce-streamer-weekly-ranking-flow"

        private const val RANKING_ENDPOINT = "/api/v1/rankings/weekly"

        /** A UTC week: 2026-08-17T00:00Z up to, not including, 2026-08-24T00:00Z. */
        private val PERIOD_START: LocalDate = LocalDate.parse("2026-08-17")
        private const val REVISION_1 = "2026-08-17-r1"
        private const val REVISION_2 = "2026-08-17-r2"

        /** Inside the period, and already in microseconds, so the order and the streamer's table keep them as they are. */
        private val FIRST_CONFIRMED_AT: Instant = Instant.parse("2026-08-18T01:02:03.123456Z")
        private val SECOND_CONFIRMED_AT: Instant = Instant.parse("2026-08-19T01:02:03.123456Z")
        private val THIRD_CONFIRMED_AT: Instant = Instant.parse("2026-08-20T01:02:03.123456Z")
        private val LATE_CONFIRMED_AT: Instant = Instant.parse("2026-08-21T01:02:03.123456Z")

        /**
         * How long after a confirmation its relay round starts. The third order's round, a day after the stopped one, is also far
         * past the 60 s lease the stopped round left on the second order's row.
         */
        private val ROUND_DELAY: Duration = Duration.ofSeconds(5)

        /** The card every confirmation sends. The number passes the Luhn check. The create stubs match this card. */
        private val CARD = CreditCard(type = "SAMSUNG", number = "4111-1111-1111-1111")

        /** Every product's price, so a confirmation's PG amount is this times the order's units. */
        private const val PRICE = 1_000L
    }

    /** Where the republish stops the relay (`stopRelayAfterBrokerAckBeforeMarkingPublished`). Unstubbed, it is the real service. */
    @MockkSpyBean
    private lateinit var orderEventOutboxModifyService: OrderEventOutboxModifyService

    /** The one exception to constructor injection, as in `OrderConfirmationApiTest`. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    private lateinit var owner: User

    private var streamer: CommerceStreamerProcess? = null

    @BeforeEach
    fun startFromTheBatchTables() {
        databaseCleanUp.truncateAllTables()
        WeeklyRankingBatchTables.reset(dataSource)
        owner = prepareUser()
    }

    /**
     * Ends the streamer before the tables are emptied, so no consumer is reading while they empty, and when the scene failed too.
     * `terminate` checks the process is gone. Both are in one method because JUnit doesn't order a class's `@AfterEach` methods.
     */
    @AfterEach
    fun terminateTheStreamerThenCleanUp() {
        try {
            streamer?.terminate()
            streamer = null
        } finally {
            databaseCleanUp.truncateAllTables()
        }
    }

    @Test
    fun `a late purchase reaches the weekly ranking only through a later revision, and a republished event counts once`() {
        prepareBrand()
        val productA = prepareProduct(brand, price = PRICE)
        val productB = prepareProduct(brand, price = PRICE)
        val productC = prepareProduct(brand, price = PRICE)
        val streamer = startTheStreamer()

        // 1. Three orders are confirmed and relayed. The second order's event is published twice.
        val firstOrderId = createdOrderId(requestCreate(productA to 2, productB to 1))
        val secondOrderId = createdOrderId(requestCreate(productA to 3))
        val thirdOrderId = createdOrderId(requestCreate(productC to 5))
        assertConfirmed(requestConfirmAt(FIRST_CONFIRMED_AT, firstOrderId, amount = 3 * PRICE))
        clock.pin(FIRST_CONFIRMED_AT.plus(ROUND_DELAY))
        newRelay().publishDueEvents()
        assertConfirmed(requestConfirmAt(SECOND_CONFIRMED_AT, secondOrderId, amount = 3 * PRICE))
        clock.pin(SECOND_CONFIRMED_AT.plus(ROUND_DELAY))
        orderEventOutboxModifyService.stopRelayAfterBrokerAckBeforeMarkingPublished()
        assertThrows<DataAccessResourceFailureException> { newRelay().publishDueEvents() }
        assertConfirmed(requestConfirmAt(THIRD_CONFIRMED_AT, thirdOrderId, amount = 5 * PRICE))
        clock.pin(THIRD_CONFIRMED_AT.plus(ROUND_DELAY))
        newRelay().publishDueEvents()

        val linesBeforeRevision1 = streamer.awaitRecordedLines(expected = 4) { rowsLine() }
        val activitiesBeforeRevision1 = storedPurchaseActivities()
        assertThat(linesBeforeRevision1).hasSize(4)
        assertThat(resultsOf(linesBeforeRevision1, firstOrderId)).containsExactly("RECORDED")
        assertThat(resultsOf(linesBeforeRevision1, secondOrderId)).containsExactly("RECORDED", "ALREADY_RECORDED")
        assertThat(resultsOf(linesBeforeRevision1, thirdOrderId)).containsExactly("RECORDED")
        assertThat(activitiesBeforeRevision1).containsExactly(
            "order $firstOrderId product ${productA.id} quantity 2 confirmed_at $FIRST_CONFIRMED_AT",
            "order $firstOrderId product ${productB.id} quantity 1 confirmed_at $FIRST_CONFIRMED_AT",
            "order $secondOrderId product ${productA.id} quantity 3 confirmed_at $SECOND_CONFIRMED_AT",
            "order $thirdOrderId product ${productC.id} quantity 5 confirmed_at $THIRD_CONFIRMED_AT",
        )

        // The fourth order is confirmed inside the period too, but no relay round runs for it yet.
        val lateOrderId = createdOrderId(requestCreate(productB to 5))
        assertConfirmed(requestConfirmAt(LATE_CONFIRMED_AT, lateOrderId, amount = 5 * PRICE))
        assertThat(outboxStatus(lateOrderId)).isEqualTo("PENDING")

        log.info {
            "Flow 1, three orders relayed and one republished: products A ${productA.id}, B ${productB.id}, C ${productC.id}; " +
                "deliveries ${linesBeforeRevision1.size} [${linesBeforeRevision1.joinToString("; ")}]; " +
                "purchase_activity [${activitiesBeforeRevision1.joinToString("; ")}]; " +
                "order $lateOrderId confirmed at $LATE_CONFIRMED_AT, its outbox row PENDING"
        }

        // 2. Nothing is published before a run.
        val beforeAnyRun = requestGetWeeklyRanking()
        val body = assertThat(beforeAnyRun).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("NOT_PUBLISHED")
        body.extractingPath("$.data.snapshotId").isNull()
        body.extractingPath("$.data.items").asArray().isEmpty()

        // 3. Revision 1. `run` returns only after an exit with 0; any other code fails it with the tail of the batch's log.
        runWeeklyRanking(REVISION_1, revision = 1)

        val afterRevision1 = requestGetWeeklyRanking()
        val bodyAfterRevision1 = assertThat(afterRevision1).hasStatusOk().bodyJson()
        bodyAfterRevision1.extractingPath("$.data.status").isEqualTo("PUBLISHED")
        bodyAfterRevision1.extractingPath("$.data.snapshotId").isEqualTo(REVISION_1)
        val revision1 = afterRevision1.json()["data"]
        assertThat(itemsOf(revision1)).containsExactly(
            "rank 1 product ${productA.id} score 5",
            "rank 2 product ${productC.id} score 5",
            "rank 3 product ${productB.id} score 1",
        )

        log.info {
            "Flow 2-3, before any run ${beforeAnyRun.json()["data"]["status"].asString()}; " +
                "revision 1 exited with 0: snapshotId ${revision1["snapshotId"].asString()}, " +
                "inputFrozenAt ${revision1["inputFrozenAt"].asString()}, items [${itemsOf(revision1).joinToString("; ")}]"
        }

        // 4. The fourth order's row is relayed and recorded: a purchase of the period, recorded after revision 1 froze.
        clock.pin(LATE_CONFIRMED_AT.plus(ROUND_DELAY))
        newRelay().publishDueEvents()

        val linesAfterTheLateRelay = streamer.awaitRecordedLines(expected = 5) { rowsLine() }
        val activitiesAfterTheLateRelay = storedPurchaseActivities()
        assertThat(linesAfterTheLateRelay).hasSize(5)
        assertThat(resultsOf(linesAfterTheLateRelay, lateOrderId)).containsExactly("RECORDED")
        assertThat(activitiesAfterTheLateRelay).isEqualTo(
            activitiesBeforeRevision1 + "order $lateOrderId product ${productB.id} quantity 5 confirmed_at $LATE_CONFIRMED_AT",
        )

        // 5. The response has not moved.
        val afterTheLatePurchase = requestGetWeeklyRanking()
        assertThat(afterTheLatePurchase).hasStatusOk()
        assertThat(afterTheLatePurchase.json()).isEqualTo(afterRevision1.json())

        log.info {
            "Flow 4-5, the late purchase recorded after revision 1 froze: " +
                "delivery [${linesAfterTheLateRelay.last()}]; purchase_activity ${activitiesAfterTheLateRelay.size} rows; " +
                "the response is unchanged: snapshotId ${afterTheLatePurchase.json()["data"]["snapshotId"].asString()}, " +
                "items [${itemsOf(afterTheLatePurchase.json()["data"]).joinToString("; ")}]"
        }

        // 6. Revision 2 includes the late purchase and replaces the publication.
        runWeeklyRanking(REVISION_2, revision = 2)

        val afterRevision2 = requestGetWeeklyRanking()
        val bodyAfterRevision2 = assertThat(afterRevision2).hasStatusOk().bodyJson()
        bodyAfterRevision2.extractingPath("$.data.status").isEqualTo("PUBLISHED")
        bodyAfterRevision2.extractingPath("$.data.snapshotId").isEqualTo(REVISION_2)
        val revision2 = afterRevision2.json()["data"]
        assertThat(itemsOf(revision2)).containsExactly(
            "rank 1 product ${productB.id} score 6",
            "rank 2 product ${productA.id} score 5",
            "rank 3 product ${productC.id} score 5",
        )
        assertThat(Instant.parse(revision2["inputFrozenAt"].asString()))
            .isAfter(Instant.parse(revision1["inputFrozenAt"].asString()))

        // 7. Both snapshots are kept, with their own input counts and checksums.
        val snapshots = storedSnapshots()
        assertThat(snapshots.map { it.describe() }).containsExactly(
            "$REVISION_1 revision 1 inputs 4",
            "$REVISION_2 revision 2 inputs 5",
        )
        assertThat(snapshots[0].checksum).isNotEqualTo(snapshots[1].checksum)
        assertThat(streamer.recordedLines()).hasSize(5)

        log.info {
            "Flow 6-7, revision 2 exited with 0: snapshotId ${revision2["snapshotId"].asString()}, " +
                "inputFrozenAt ${revision2["inputFrozenAt"].asString()}, items [${itemsOf(revision2).joinToString("; ")}]; " +
                "snapshots [${snapshots.joinToString("; ") { "${it.describe()} checksum ${it.checksum}" }}]"
        }
    }

    /** Starts the streamer on the context's MySQL and broker, with the class's topic and group, and keeps it for the clean-up. */
    private fun startTheStreamer(): CommerceStreamerProcess {
        val settings = CommerceStreamerProcess.Settings.of(environment, kafkaProperties, orderEventsProperties, GROUP)

        return CommerceStreamerProcess.start(settings).also { streamer = it }
    }

    /** What a restart makes: a new relay on the context's beans. */
    private fun newRelay(): OrderEventRelay {
        return OrderEventRelay(orderEventOutboxModifyService, orderEventPublisher)
    }

    /** Runs the batch's job to its exit. `run` returns only after an exit with 0, so the scene asserts no exit code itself. */
    private fun runWeeklyRanking(snapshotId: String, revision: Long) {
        CommerceBatchProcess.run(
            CommerceBatchProcess.Settings.of(environment),
            CommerceBatchProcess.WeeklyRankingParameters(PERIOD_START, snapshotId, revision),
        )
    }

    /** [owner] creates an order of [items]. */
    private fun requestCreate(vararg items: Pair<Product, Int>): MvcTestResult {
        val json = items.joinToString(",") { (product, quantity) -> """{"productId":${product.id},"quantity":$quantity}""" }

        return mvc.post().uri("/api/v1/orders")
            .header(UserIdHeader.NAME, owner.id)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"items":[$json]}""")
            .exchange()
    }

    private fun createdOrderId(created: MvcTestResult): Long {
        val body = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson()

        return body.extractingPath("$.data.orderId").asNumber().actual().toLong()
    }

    /**
     * [owner] confirms the order with the clock pinned at [confirmedAt], the test PG accepting a create of [amount]. The clock stays
     * there: the scene pins it again before the relay round that follows.
     */
    private fun requestConfirmAt(confirmedAt: Instant, orderId: Long, amount: Long): MvcTestResult {
        clock.pin(confirmedAt)
        acceptNextCreate(testPg, amount = amount, card = CARD)

        return mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .header(UserIdHeader.NAME, owner.id)
            .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"cardType":"${CARD.type}","cardNo":"${CARD.number}"}""")
            .exchange()
    }

    private fun assertConfirmed(confirmed: MvcTestResult) {
        val body = assertThat(confirmed).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("CONFIRMED")
    }

    private fun requestGetWeeklyRanking(): MvcTestResult {
        return mvc.get().uri(RANKING_ENDPOINT).param("periodStart", PERIOD_START.toString()).exchange()
    }

    /** The ranking's items, one line each, in the response's order. */
    private fun itemsOf(ranking: JsonNode): List<String> {
        return ranking["items"].asIterable().map { item ->
            "rank ${item["rank"].asInt()} product ${item["product"]["id"].asLong()} score ${item["score"].asLong()}"
        }
    }

    /** The `result=` of each delivery of [orderId]'s event among the streamer's [lines], in the order it logged them. */
    private fun resultsOf(lines: List<String>, orderId: Long): List<String> {
        return lines.filter { "eventId=${eventIdOf(orderId)}," in it }.map { it.substringAfter("result=") }
    }

    /**
     * The purchase activities the streamer committed, one line each. The table has no entity in commerce-api, and the time is
     * formatted by MySQL as stored (UTC): a JDBC read would shift it into the test JVM's zone.
     */
    private fun storedPurchaseActivities(): List<String> {
        return jdbc.queryForList(
            "select concat('order ', order_id, ' product ', product_id, ' quantity ', quantity, " +
                "' confirmed_at ', date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ')) " +
                "from purchase_activity order by order_id, product_id",
            String::class.java,
        ).filterNotNull()
    }

    private fun outboxStatus(orderId: Long): String {
        return jdbc.queryForObject(
            "select status from order_event_outbox where event_id = ?",
            String::class.java,
            eventIdOf(orderId),
        )!!
    }

    private fun eventIdOf(orderId: Long): String {
        return "order-confirmed:v1:$orderId"
    }

    /** The rows a wait prints when it runs out: the outbox rows and the purchase activities. */
    private fun rowsLine(): String {
        val outboxes = jdbc.queryForList(
            "select concat(event_id, ' ', status, ' attempts ', attempt_count) from order_event_outbox order by id",
            String::class.java,
        ).filterNotNull()

        return "outbox rows $outboxes; purchase_activity ${storedPurchaseActivities()}"
    }

    /** Every snapshot the batch left, in revision order. */
    private fun storedSnapshots(): List<StoredSnapshot> {
        return jdbc.query(
            "select snapshot_id, revision, input_count, input_checksum from weekly_ranking_snapshot order by revision",
        ) { row, _ ->
            StoredSnapshot(
                snapshotId = row.getString("snapshot_id"),
                revision = row.getLong("revision"),
                inputCount = row.getLong("input_count"),
                checksum = row.getString("input_checksum"),
            )
        }
    }

    private fun MvcTestResult.json(): JsonNode {
        return objectMapper.readTree(response.contentAsString)
    }

    private data class StoredSnapshot(
        val snapshotId: String,
        val revision: Long,
        val inputCount: Long,
        val checksum: String,
    ) {
        fun describe(): String {
            return "$snapshotId revision $revision inputs $inputCount"
        }
    }
}
