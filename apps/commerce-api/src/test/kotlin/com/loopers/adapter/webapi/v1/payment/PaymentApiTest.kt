package com.loopers.adapter.webapi.v1.payment

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.productStock
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.util.UUID

/**
 * PG의 알림이 실제 컨트롤러를 거쳐 결제를 대사하는 HTTP 계약을 테스트 PG와 함께 확인한다(ADR 0031). 답의 표(400, 404, 503, 200)와, 본문의
 * 거래 키와 PG 상태가 어느 판단에도 들지 않는 것을 본다. 테스트 PG는 알림을 보내지 않으므로 테스트가 컨트롤러에 알림을 직접 보낸다.
 *
 * 알림을 받을 결제는 실제 확정으로 만든다. 확정은 트랜잭션 밖에서만 돌고(ADR 0028 규칙 2) 요청마다 커밋한 결과를 다음 요청이 읽으므로 테스트
 * 트랜잭션에서 빠진다. 다시 읽는 것은 새 영속성 컨텍스트의 요청이다. 결제가 받을 PG 주문 번호는 확정 전에 `nextPgOrderId`로 정해 스텁에 넣는다.
 *
 * 확정이 준비일 뿐이지만 기반의 `confirmOrder`를 부르지 않고 확정 요청을 보낸다. 알림의 본문과 조회 스텁에 결제의 PG 주문 번호와 거래 키가
 * 들어가야 하는데, `confirmOrder`는 거래 키를 매번 새로 뽑고 PG 주문 번호를 돌려주지 않는다.
 */
@EnableTestPg
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentApiTest(
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    private val clock: TestClock,
) : BaseWebApiAdapterTest() {
    companion object {
        /** 확정이 싣는 카드. 카드 번호는 Luhn 검사를 지난다. 생성 스텁은 이 카드를 맞춘다. */
        private val CARD = CreditCard(type = "SAMSUNG", number = "4111-1111-1111-1111")

        private const val TRANSACTION_KEY = "20261023:TR:cb0001"

        /** 결제 금액. 상품 하나(1,000원)를 2개 주문한다. */
        private const val AMOUNT = 2_000L
    }

    /** 주문의 주인. 알림은 요청자를 싣지 않으므로 확정과 상세 조회만 쓴다. */
    private lateinit var owner: User

    /** 생성자 주입의 하나뿐인 예외다. 까닭은 `OrderPaymentIntegrationTest`의 같은 필드에 있다. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        owner = prepareUser()
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    /**
     * 결제 중인 결제의 알림이 그 PG 주문 번호로 PG에 조회해, 찾은 성공으로 주문을 확정한다. 요청자 헤더 없이 받고, 200과 data 없는 성공 응답으로
     * 답한다. 생성은 다시 보내지 않고 예약은 소비한다.
     */
    @Test
    fun `a callback confirms a pending payment the PG's lookup reports successful and answers 200 without a requester`() {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment()
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, PgStatus.SUCCESS))

        val body = assertThat(requestCallback(callback(pgOrderId, status = "SUCCESS"))).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.doesNotHavePath("$.data")

        val detail = assertThat(requestDetail()).hasStatusOk().bodyJson()
        detail.extractingPath("$.data.status").isEqualTo("CONFIRMED")
        detail.extractingPath("$.data.paymentStatus").isEqualTo("SUCCESS")
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertThat(entityManager.productStock(product.id)).isEqualTo(8)
    }

    /**
     * PG 거래가 실패면 주문은 결제에 실패한 주문이 되고 예약을 돌려준다. 같은 알림을 두 번 받아도 확정은 한 번이고, 실패의 반환도 한 번이라 재고는
     * 10이다. 두 번 돌려주면 12다. 둘째 알림은 끝난 결제를 만나 조회하지 않으므로 조회는 1이고, 두 알림 모두 200이다.
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, SUCCESS, 8", "FAILED, PAYMENT_FAILED, FAILED, 10")
    fun `two identical callbacks change the order and the stock once`(
        pgStatus: PgStatus,
        orderStatus: String,
        paymentStatus: String,
        stock: Int,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment()
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, pgStatus))
        val json = callback(pgOrderId, status = pgStatus.name)

        assertThat(requestCallback(json)).hasStatusOk()
        val finished = requestDetail()
        assertThat(finished).hasStatusOk()
        assertThat(requestCallback(json)).hasStatusOk()

        val duplicated = requestDetail()
        val body = assertThat(duplicated).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo(orderStatus)
        body.extractingPath("$.data.paymentStatus").isEqualTo(paymentStatus)
        assertThat(duplicated.response.contentAsString).isEqualTo(finished.response.contentAsString)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertThat(entityManager.productStock(product.id)).isEqualTo(stock)
    }

    /**
     * 알림의 PG 상태는 결과가 아니다(ADR 0031 규칙 5). 본문이 성공을 말해도 PG의 조회가 처리 중이면 주문은 결제 중 그대로이고 예약도 그대로다.
     * 대사는 끝났으므로 200이다.
     */
    @Test
    fun `a callback saying SUCCESS while the PG's lookup says PENDING leaves the order payment pending and the stock reserved`() {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment()
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, PgStatus.PENDING))

        assertThat(requestCallback(callback(pgOrderId, status = "SUCCESS"))).hasStatusOk()

        val body = assertThat(requestDetail()).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("PAYMENT_PENDING")
        body.extractingPath("$.data.paymentStatus").isEqualTo("PENDING")
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertThat(entityManager.productStock(product.id)).isEqualTo(8)
    }

    /**
     * 알림의 거래 키도 판단에 들지 않는다. 본문이 다른 거래 키를 말해도 PG 주문 번호로 조회한 PG 거래를 그대로 반영해, 결제는 PG가 말한 거래 키로
     * 끝난다(ADR 0031 규칙 4).
     */
    @Test
    fun `a callback naming another transaction key applies the transaction the PG's lookup finds`() {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment()
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, PgStatus.SUCCESS))

        assertThat(requestCallback(callback(pgOrderId, status = "SUCCESS", transactionKey = "20261023:TR:forged"))).hasStatusOk()

        val body = assertThat(requestDetail()).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("CONFIRMED")
        assertThat(jdbc.queryForObject("select transaction_key from payment where order_id = ?", String::class.java, order.id))
            .isEqualTo(TRANSACTION_KEY)
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * 읽지 않는 필드가 없거나, `null`이거나, 낯선 값이거나, 모르는 필드가 더 있어도 알림을 잃지 않는다(ADR 0031 규칙 1). 모두 조회한 성공으로
     * 주문을 확정하고 200이다. [fields]는 `orderId` 뒤에 잇는 JSON이다.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            ""","transactionKey":null,"status":null""",
            ""","transactionKey":"unknown","status":"NOT_A_PG_STATUS"""",
            ""","status":"success","reason":"extra field"""",
        ],
    )
    fun `a callback whose transaction key or status is missing, null, unknown or joined by extra fields still reconciles`(
        fields: String,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment()
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, PgStatus.SUCCESS))

        assertThat(requestCallback("""{"orderId":"$pgOrderId"$fields}""")).hasStatusOk()

        val body = assertThat(requestDetail()).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("CONFIRMED")
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * 조회가 PG 거래를 찾지 못했거나, 여럿을 찾았거나, 결제와 금액이 다른 PG 거래를 찾으면 아무것도 반영하지 않는다. 그래도 대사는 끝났으므로
     * 200이다. 결제와 예약은 그대로다(ADR 0031 규칙 6).
     */
    @ParameterizedTest
    @ValueSource(strings = ["none", "several", "another amount"])
    fun `a callback whose lookup finds no, several or a mismatched transaction answers 200 and changes nothing`(answer: String) {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment()
        when (answer) {
            "none" -> testPg.hasTransactions(pgOrderId)

            "several" -> testPg.hasTransactions(
                pgOrderId,
                pgTransaction(pgOrderId, PgStatus.SUCCESS),
                pgTransaction(pgOrderId, PgStatus.FAILED, transactionKey = "20261023:TR:other"),
            )

            "another amount" -> testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, PgStatus.SUCCESS, amount = 1_000))
        }

        assertThat(requestCallback(callback(pgOrderId, status = "SUCCESS"))).hasStatusOk()

        val body = assertThat(requestDetail()).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("PAYMENT_PENDING")
        body.extractingPath("$.data.paymentStatus").isEqualTo("PENDING")
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertThat(entityManager.productStock(product.id)).isEqualTo(8)
    }

    /** 결제가 끝난 주문의 알림은 PG에 조회하지 않고 200이다. 늦은 알림이 끝난 결제를 되돌리지 않고, 실패의 반환도 다시 일어나지 않는다. */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, 8", "FAILED, PAYMENT_FAILED, 10")
    fun `a callback for a finished payment answers 200 without a lookup and changes nothing`(
        pgStatus: String,
        orderStatus: String,
        stock: Int,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment(pgStatus = pgStatus)
        val finished = requestDetail()
        assertThat(finished).hasStatusOk()

        assertThat(requestCallback(callback(pgOrderId, status = "PENDING"))).hasStatusOk()

        val late = requestDetail()
        val body = assertThat(late).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo(orderStatus)
        assertThat(late.response.contentAsString).isEqualTo(finished.response.contentAsString)
        assertThat(testPg.lookupCount()).isZero()
        assertThat(entityManager.productStock(product.id)).isEqualTo(stock)
    }

    /**
     * 조회할 수 없으면 503 `PG_UNAVAILABLE`이다. 묻지 못했으니 PG가 다시 보내면 답이 바뀔 수 있다(ADR 0031 규칙 6). 결제와 예약은 그대로다.
     * The lookup is retried once before the callback gives up, so the PG receives 2 lookups (ADR 0030 rule 4).
     */
    @ParameterizedTest
    @ValueSource(strings = ["503", "lost response"])
    fun `a callback whose lookup is unavailable answers 503 PG_UNAVAILABLE and leaves the payment unchanged`(
        unavailable: String,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment()
        val pending = requestDetail()
        assertThat(pending).hasStatusOk()
        if (unavailable == "503") {
            testPg.answersList(pgOrderId, httpStatus = 503)
        } else {
            testPg.losesLookupResponse()
        }

        val body = assertThat(requestCallback(callback(pgOrderId, status = "SUCCESS")))
            .hasStatus(HttpStatus.SERVICE_UNAVAILABLE).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("PG_UNAVAILABLE")

        assertThat(requestDetail().response.contentAsString).isEqualTo(pending.response.contentAsString)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertThat(entityManager.productStock(product.id)).isEqualTo(8)
    }

    /**
     * While the lookup breaker is open, a callback answers 503 `PG_UNAVAILABLE` without sending the PG a lookup, so a resending
     * PG tries again later (ADR 0031 rule 6). The breaker opens the way it would in production: two callbacks against a PG
     * that drops every lookup response, 4 lookups in all. The payment and the reservation stay as they were throughout. Once
     * the 60 s wait is over and the PG answers again, the same callback probes, answers 200 and confirms the order.
     */
    @Test
    fun `a callback while the lookup breaker is open answers 503 without a lookup and finishes the payment after the wait`() {
        prepareProduct(price = 1_000, stock = 10)
        val pgOrderId = preparePayment()
        val pending = requestDetail()
        assertThat(pending).hasStatusOk()
        testPg.losesLookupResponse()
        val json = callback(pgOrderId, status = "SUCCESS")
        repeat(2) { assertThat(requestCallback(json)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE) }
        assertThat(testPg.lookupCount()).isEqualTo(4)

        val body = assertThat(requestCallback(json)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("PG_UNAVAILABLE")

        assertThat(testPg.lookupCount()).isEqualTo(4)
        assertThat(requestDetail().response.contentAsString).isEqualTo(pending.response.contentAsString)
        assertThat(entityManager.productStock(product.id)).isEqualTo(8)

        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, PgStatus.SUCCESS))
        clock.advance(Duration.ofSeconds(61))

        assertThat(requestCallback(json)).hasStatusOk()

        val detail = assertThat(requestDetail()).hasStatusOk().bodyJson()
        detail.extractingPath("$.data.status").isEqualTo("CONFIRMED")
        detail.extractingPath("$.data.paymentStatus").isEqualTo("SUCCESS")
        assertThat(testPg.lookupCount()).isEqualTo(5)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertThat(entityManager.productStock(product.id)).isEqualTo(8)
    }

    /** 모르는 PG 주문 번호는 404 `PAYMENT_NOT_FOUND`다. DB를 한 번 읽을 뿐 PG에 조회하지 않고, 다른 결제도 바뀌지 않는다(ADR 0031 규칙 2). */
    @Test
    fun `a callback for an unknown PG order ID answers 404 PAYMENT_NOT_FOUND without a lookup`() {
        prepareProduct(price = 1_000, stock = 10)
        preparePayment()
        val pending = requestDetail()
        assertThat(pending).hasStatusOk()

        val body = assertThat(requestCallback(callback(UUID.randomUUID().toString(), status = "SUCCESS")))
            .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("PAYMENT_NOT_FOUND")

        assertThat(requestDetail().response.contentAsString).isEqualTo(pending.response.contentAsString)
        assertThat(testPg.lookupCount()).isZero()
    }

    /** 형식이 틀린 본문은 결제를 찾기 전에 400이다. 고칠 것은 PG 쪽이고, PG에 아무것도 묻지 않는다(ADR 0031 규칙 6). */
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"transactionKey":"20261023:TR:cb0001","status":"SUCCESS"}""",
            """{"orderId":null,"status":"SUCCESS"}""",
            """{"orderId":"","status":"SUCCESS"}""",
            """{"orderId":"   ","status":"SUCCESS"}""",
            "not json",
        ],
    )
    fun `a callback without a PG order ID or with a body that isn't JSON answers 400 without a lookup`(json: String) {
        val body = assertThat(requestCallback(json)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")

        assertThat(testPg.requestCount()).isZero()
    }

    /**
     * 주인이 [AMOUNT]원 주문을 확정하고, 테스트 PG가 그 생성을 PG 상태 [pgStatus]의 PG 거래로 받아들인다. 처리 중이면 결제 중인 주문으로 202,
     * 끝났으면 200이다. 결제의 PG 주문 번호를 돌려준다. 상품은 미리 준비해 둔 하나다.
     */
    private fun preparePayment(pgStatus: String = "PENDING"): String {
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(pgOrderId, amount = AMOUNT, card = CARD, transactionKey = TRANSACTION_KEY, pgStatus = pgStatus)
        val confirmed = mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .header(UserIdHeader.NAME, owner.id)
            .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"cardType":"${CARD.type}","cardNo":"${CARD.number}"}""")
        assertThat(confirmed).hasStatus(if (pgStatus == "PENDING") HttpStatus.ACCEPTED else HttpStatus.OK)

        return pgOrderId
    }

    /** PG 주문 번호 [pgOrderId]로 조회되는 PG 거래. 금액은 따로 넘기지 않으면 결제의 [AMOUNT]원이다. */
    private fun pgTransaction(
        pgOrderId: String,
        status: PgStatus,
        transactionKey: String = TRANSACTION_KEY,
        amount: Long = AMOUNT,
    ): PgTransaction {
        return PgTransaction(transactionKey = transactionKey, pgOrderId = pgOrderId, amount = Money(amount), status = status)
    }

    /** PG 계약의 알림 본문. 거래 키는 따로 넘기지 않으면 생성 스텁의 [TRANSACTION_KEY]다. */
    private fun callback(pgOrderId: String, status: String, transactionKey: String = TRANSACTION_KEY): String {
        return """{"transactionKey":"$transactionKey","orderId":"$pgOrderId","status":"$status"}"""
    }

    /** 알림은 요청자 헤더도 멱등 키도 싣지 않는다(ADR 0031 규칙 7). */
    private fun requestCallback(json: String): MvcTestResult {
        return mvc.post().uri("/api/v1/payments/callback")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()
    }

    private fun requestDetail(): MvcTestResult {
        return mvc.get().uri("/api/v1/orders/${order.id}").header(UserIdHeader.NAME, owner.id).exchange()
    }
}
