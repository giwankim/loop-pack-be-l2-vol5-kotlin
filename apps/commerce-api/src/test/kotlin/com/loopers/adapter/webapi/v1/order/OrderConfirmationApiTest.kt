package com.loopers.adapter.webapi.v1.order

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.wiremock.spring.InjectWireMock
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 주문 확정의 HTTP 계약을 테스트 PG와 함께 확인한다(ADR 0024, 0025). 멱등 키와 카드의 검사, 200과 202, 응답의 결제 필드, 상세 조회를 본다.
 *
 * 확정은 트랜잭션 밖에서만 돌고(ADR 0028 규칙 2) 요청마다 커밋한 결과를 다음 요청이 읽으므로 테스트 트랜잭션에서 빠진다.
 * 다시 읽는 것은 새 영속성 컨텍스트의 요청이나 새 트랜잭션이다. 결제가 받을 PG 주문 번호는 확정 전에 `nextPgOrderId`로 정해 생성 스텁에 넣는다.
 */
@EnableTestPg
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderConfirmationApiTest(
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : BaseWebApiAdapterTest() {
    companion object {
        /** 요청 도우미가 기본으로 싣는 카드. 카드 번호는 Luhn 검사를 지난다. 생성 스텁은 이 카드를 맞춘다. */
        private val CARD = CreditCard(type = "SAMSUNG", number = "4111-1111-1111-1111")

        private const val TRANSACTION_KEY = "20261022:TR:api001"

        /** [CARD]가 아닌 카드의 본문. 카드 번호는 Luhn 검사를 지난다. */
        private const val OTHER_CARD_JSON = """{"cardType":"KB","cardNo":"5555-5555-5555-4444"}"""

        @JvmStatic
        fun malformedKeys(): List<Arguments> {
            return listOf(
                Arguments.of("", "멱등 키는 공백일 수 없습니다."),
                Arguments.of("a".repeat(65), "멱등 키는 64자 이하여야 합니다."),
                Arguments.of("confirm key", "멱등 키는 공백 없는 보이는 ASCII 문자여야 합니다."),
                Arguments.of("confirm\tkey", "멱등 키는 공백 없는 보이는 ASCII 문자여야 합니다."),
                Arguments.of("확정-키", "멱등 키는 공백 없는 보이는 ASCII 문자여야 합니다."),
            )
        }
    }

    private val transaction = TransactionTemplate(transactionManager)

    /**
     * 요청 도우미가 기본으로 싣는 요청자이자 주문의 주인. [setUp]이 준비하고, 다른 주인이 필요한 테스트는 바꿔 넣는다.
     * 다른 사용자를 준비하는 테스트가 기반 클래스의 `user` 필드를 바꾸므로 그 필드가 아닌 반환값을 따로 쥔다.
     */
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

    @Test
    fun `creating and confirming pays once through the PG and reads back the confirmed order with its payment fields`() {
        prepareBrand()
        val first = prepareProduct(brand, price = 1_000, stock = 10)
        val second = prepareProduct(brand, price = 2_000, stock = 5)
        val orderId = prepareOrder(second to 1, first to 5, user = owner).id
        val draft = requestDetail(orderId)
        val body = assertThat(draft).hasStatusOk().bodyJson()
        body.extractingPath("$.data.totalAmount").isEqualTo(7_000)
        body.doesNotHavePath("$.data.paymentMethod")
        body.doesNotHavePath("$.data.paymentStatus")
        body.doesNotHavePath("$.data.paidAmount")
        acceptCreate(amount = 7_000)

        val confirmed = requestConfirm(orderId)
        val confirmBody = assertThat(confirmed).hasStatusOk().bodyJson()
        confirmBody.extractingPath("$.data.status").isEqualTo("CONFIRMED")
        confirmBody.extractingPath("$.data.paymentMethod").isEqualTo("PG")
        confirmBody.extractingPath("$.data.paymentStatus").isEqualTo("SUCCESS")
        confirmBody.extractingPath("$.data.paidAmount").isEqualTo(7_000)
        confirmBody.extractingPath("$.data.totalAmount").isEqualTo(7_000)
        confirmBody.doesNotHavePath("$.data.confirmedAt")

        assertThat(confirmed.json()["data"]["items"]).isEqualTo(draft.json()["data"]["items"])
        assertThat(confirmed.json()["data"]["createdAt"]).isEqualTo(draft.json()["data"]["createdAt"])
        assertThat(testPg.createCount()).isOne()
        assertStock(first, 5)
        assertStock(second, 4)
        val confirmedDetail = requestDetail(orderId)
        assertThat(confirmedDetail).hasStatusOk()
        assertThat(confirmedDetail.json()).isEqualTo(confirmed.json())
        assertKeyMismatch(orderId)
        assertThat(testPg.createCount()).isOne()
        assertStock(first, 5)
        assertStock(second, 4)
    }

    /** PG가 받아들였지만 처리 중이면 결과가 아직 없다. 202와 함께 결과를 읽을 상세의 위치를 준다(ADR 0025 규칙 3). */
    @Test
    fun `a confirmation the PG accepts as pending returns 202 with the order's location and the detail reads the same body`() {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        acceptCreate(amount = 2_000, pgStatus = "PENDING")

        val accepted = requestConfirm(orderId)
        val body = assertThat(accepted).hasStatus(HttpStatus.ACCEPTED).bodyJson()
        assertThat(accepted).headers().hasValue(HttpHeaders.LOCATION, "/api/v1/orders/$orderId")
        body.extractingPath("$.data.status").isEqualTo("PAYMENT_PENDING")
        body.extractingPath("$.data.paymentMethod").isEqualTo("PG")
        body.extractingPath("$.data.paymentStatus").isEqualTo("PENDING")
        body.doesNotHavePath("$.data.paidAmount")

        val pendingDetail = requestDetail(orderId)
        assertThat(pendingDetail).hasStatusOk()
        assertThat(pendingDetail.json()).isEqualTo(accepted.json())
        assertThat(testPg.createCount()).isOne()
        assertStock(product, 8)
    }

    /** 응답을 잃은 생성은 실패가 아니다. 결제는 결과 미확인, 주문은 결제 중인 주문으로 202이고 예약은 그대로다(ADR 0024 규칙 4). */
    @Test
    fun `a confirmation whose PG response is lost returns 202 with the payment unknown and the stock reserved`() {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        testPg.losesCreateResponse(pgOrderId = nextPgOrderId(), amount = 2_000, card = CARD)

        val accepted = requestConfirm(orderId)
        val body = assertThat(accepted).hasStatus(HttpStatus.ACCEPTED).bodyJson()
        assertThat(accepted).headers().hasValue(HttpHeaders.LOCATION, "/api/v1/orders/$orderId")
        body.extractingPath("$.data.status").isEqualTo("PAYMENT_PENDING")
        body.extractingPath("$.data.paymentStatus").isEqualTo("UNKNOWN")

        assertThat(testPg.createCount()).isOne()
        assertStock(product, 8)
    }

    /**
     * 생성 전 거절은 결제에 실패한 주문으로 끝나는 결과다. 결제 중이 아니므로 202가 아니라 200이고 `Location`이 없다(ADR 0025 규칙 3).
     * 확정되지 않았으므로 결제액이 없고, 돌려준 예약으로 재고는 확정 전과 같다. 상세도 같은 본문이다.
     */
    @Test
    fun `a confirmation the PG rejects before creating returns 200 with a payment failed order and the stock returned`() {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        testPg.rejectsBeforeCreate(pgOrderId = nextPgOrderId(), amount = 2_000, card = CARD)

        val failed = requestConfirm(orderId)
        val body = assertThat(failed).hasStatusOk().bodyJson()
        assertThat(failed).headers().doesNotContainHeader(HttpHeaders.LOCATION)
        body.extractingPath("$.data.status").isEqualTo("PAYMENT_FAILED")
        body.extractingPath("$.data.paymentMethod").isEqualTo("PG")
        body.extractingPath("$.data.paymentStatus").isEqualTo("FAILED")
        body.extractingPath("$.data.totalAmount").isEqualTo(2_000)
        body.doesNotHavePath("$.data.paidAmount")

        val failedDetail = requestDetail(orderId)
        assertThat(failedDetail).hasStatusOk()
        assertThat(failedDetail.json()).isEqualTo(failed.json())
        assertThat(testPg.createCount()).isOne()
        assertStock(product, 10)
    }

    /** 결제의 거래 키, PG 주문 번호, 카드는 주문 슬라이스가 옮기기만 하고 응답에 싣지 않는다(ADR 0028 규칙 5). 카드 번호는 숫자만 남겨서도 찾는다. */
    @ParameterizedTest
    @CsvSource("SUCCESS, OK", "PENDING, ACCEPTED", "FAILED, OK")
    fun `no confirmation or detail response carries the transaction key, the PG order ID or the card`(
        pgStatus: String,
        status: HttpStatus,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(
            pgOrderId = pgOrderId,
            amount = 1_000,
            card = CARD,
            transactionKey = TRANSACTION_KEY,
            pgStatus = pgStatus,
        )

        val confirmed = requestConfirm(orderId)
        val readBack = requestDetail(orderId)

        assertThat(confirmed).hasStatus(status)
        assertThat(readBack).hasStatusOk()
        listOf(confirmed, readBack).forEach { result ->
            assertThat(result.response.contentAsString)
                .doesNotContain(TRANSACTION_KEY, pgOrderId, CARD.number, CARD.number.filter(Char::isDigit))
        }
    }

    /**
     * 같은 키로 다시 확정하면 저장된 상태로 답한다(ADR 0025 규칙 3). 결제 중이면 첫 응답처럼 202와 Location이고 본문도 같다. PG에는 생성도 조회도
     * 더 가지 않고 재고도 다시 예약하지 않는다. 카드는 저장하지 않으므로 다른 카드여도 견주지 않는다.
     */
    @Test
    fun `confirming again with the same key while the payment is pending replays 202 with the location and sends nothing`() {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        acceptCreate(amount = 2_000, pgStatus = "PENDING")
        val first = requestConfirm(orderId, idempotencyKey = "replayed-key")
        assertThat(first).hasStatus(HttpStatus.ACCEPTED)

        val replayed = requestConfirm(orderId, idempotencyKey = "replayed-key", json = OTHER_CARD_JSON)
        assertThat(replayed).hasStatus(HttpStatus.ACCEPTED)
        assertThat(replayed).headers().hasValue(HttpHeaders.LOCATION, "/api/v1/orders/$orderId")
        assertThat(replayed.json()).isEqualTo(first.json())

        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isZero()
        assertStock(product, 8)
    }

    /** 결제가 끝난 주문의 재요청은 성공이든 실패든 첫 응답처럼 200이고 Location이 없으며 본문도 같다. 실패의 반환도 다시 일어나지 않는다. */
    @ParameterizedTest
    @CsvSource("SUCCESS, 8", "FAILED, 10")
    fun `confirming again with the same key after the payment finished replays 200 with the first body and sends nothing`(
        pgStatus: String,
        stock: Int,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        acceptCreate(amount = 2_000, pgStatus = pgStatus)
        val first = requestConfirm(orderId, idempotencyKey = "replayed-key")
        assertThat(first).hasStatusOk()

        val replayed = requestConfirm(orderId, idempotencyKey = "replayed-key", json = OTHER_CARD_JSON)
        assertThat(replayed).hasStatusOk()
        assertThat(replayed).headers().doesNotContainHeader(HttpHeaders.LOCATION)
        assertThat(replayed.json()).isEqualTo(first.json())

        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isZero()
        assertStock(product, stock)
    }

    /**
     * 결제 중인 주문의 결과는 대사로 되찾는다(ADR 0024 규칙 5). 202를 받은 확정 뒤 대사가 PG 거래의 성공이나 실패를 찾으면 200이고 `Location`이
     * 없다. 그 뒤 같은 키의 재요청과 상세도 같은 본문이다(ADR 0025 규칙 3). 대사는 생성을 보내지 않고 조회를 한 번 보낸다. 실패면 예약을
     * 돌려줘 재고가 10이다.
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, SUCCESS, 8", "FAILED, PAYMENT_FAILED, FAILED, 10")
    fun `a reconcile that finds the pending payment finished returns 200, and a same-key replay then returns the same body`(
        pgStatus: PgStatus,
        orderStatus: String,
        paymentStatus: String,
        stock: Int,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(pgOrderId, amount = 2_000, card = CARD, transactionKey = TRANSACTION_KEY, pgStatus = "PENDING")
        assertThat(requestConfirm(orderId, idempotencyKey = "replayed-key")).hasStatus(HttpStatus.ACCEPTED)
        testPg.hasTransactions(
            pgOrderId,
            PgTransaction(transactionKey = TRANSACTION_KEY, pgOrderId = pgOrderId, amount = Money(2_000), status = pgStatus),
        )

        val reconciled = requestReconcile(orderId)
        val body = assertThat(reconciled).hasStatusOk().bodyJson()
        assertThat(reconciled).headers().doesNotContainHeader(HttpHeaders.LOCATION)
        body.extractingPath("$.data.status").isEqualTo(orderStatus)
        body.extractingPath("$.data.paymentMethod").isEqualTo("PG")
        body.extractingPath("$.data.paymentStatus").isEqualTo(paymentStatus)

        val replayed = requestConfirm(orderId, idempotencyKey = "replayed-key")
        assertThat(replayed).hasStatusOk()
        assertThat(replayed.json()).isEqualTo(reconciled.json())
        assertThat(requestDetail(orderId).json()).isEqualTo(reconciled.json())
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertStock(product, stock)
    }

    /**
     * PG를 조회할 수 없는 것은 PG 거래가 없다는 뜻이 아니다. 대사는 아무것도 바꾸지 않고 결제 중인 주문으로 202와 `Location`을 돌려주며,
     * 본문은 첫 확정과 상세와 같다(ADR 0028 규칙 3). 생성은 다시 보내지 않는다. 조회는 한 번 다시 시도하므로 PG가 받은 조회는 2다(ADR 0030 규칙 4).
     */
    @Test
    fun `reconciling while the PG lookup is unavailable returns 202 with the location and changes nothing`() {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(pgOrderId, amount = 2_000, card = CARD, transactionKey = TRANSACTION_KEY, pgStatus = "PENDING")
        val accepted = requestConfirm(orderId)
        assertThat(accepted).hasStatus(HttpStatus.ACCEPTED)
        testPg.answersList(pgOrderId, httpStatus = 503)

        val reconciled = requestReconcile(orderId)
        val body = assertThat(reconciled).hasStatus(HttpStatus.ACCEPTED).bodyJson()
        assertThat(reconciled).headers().hasValue(HttpHeaders.LOCATION, "/api/v1/orders/$orderId")
        body.extractingPath("$.data.status").isEqualTo("PAYMENT_PENDING")
        body.extractingPath("$.data.paymentStatus").isEqualTo("PENDING")

        assertThat(reconciled.json()).isEqualTo(accepted.json())
        assertThat(requestDetail(orderId).json()).isEqualTo(accepted.json())
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertStock(product, 8)
    }

    /** 요청자와 소유를 PG보다 먼저 본다. 남의 주문은 결제 중이어도 없는 주문과 같은 404이고, 어느 거절도 PG에 조회하지 않는다. */
    @Test
    fun `reconciling without a requester, a missing order or another user's order is rejected before any lookup`() {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        acceptCreate(amount = 1_000, pgStatus = "PENDING")
        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)
        val pending = requestDetail(orderId)
        assertThat(pending).hasStatusOk()
        val otherUser = prepareUser().id

        val body = assertThat(requestReconcile(orderId, requester = null)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        val missing = requestReconcile(Long.MAX_VALUE)
        val error = assertThat(missing).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("ORDER_NOT_FOUND")
        val forbidden = requestReconcile(orderId, otherUser)
        assertThat(forbidden).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(forbidden.json()).isEqualTo(missing.json())

        assertThat(requestDetail(orderId).json()).isEqualTo(pending.json())
        assertThat(testPg.lookupCount()).isZero()
    }

    /**
     * 결제가 끝난 주문과 포인트로 확정된 이전 주문은 PG에 조회하지 않고 저장된 상태로 200이다. 몇 번을 물어도 바뀌는 것이 없고, 실패한 결제의
     * 반환도 다시 일어나지 않는다. 포인트로 확정된 주문은 이제 만들 길이 없어 준비한 초안 위에 SQL로 덮어쓴다(설계 13).
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, 8", "FAILED, 10", "POINT, 10")
    fun `reconciling a finished or point-paid order returns 200 with the stored state and sends no lookup`(
        paid: String,
        stock: Int,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        if (paid == "POINT") {
            jdbc.update(
                "update orders set status = 'CONFIRMED', payment_method = 'POINT', confirmed_at = now(6) where id = ?",
                orderId,
            )
        } else {
            acceptCreate(amount = 2_000, pgStatus = paid)
            assertThat(requestConfirm(orderId)).hasStatusOk()
        }
        val stored = requestDetail(orderId)
        assertThat(stored).hasStatusOk()

        repeat(2) {
            val reconciled = requestReconcile(orderId)
            assertThat(reconciled).hasStatusOk()
            assertThat(reconciled).headers().doesNotContainHeader(HttpHeaders.LOCATION)
            assertThat(reconciled.json()).isEqualTo(stored.json())
        }

        assertThat(testPg.lookupCount()).isZero()
        assertStock(product, stock)
    }

    /** 확정 전 주문에는 대사할 결제가 없다. PG에 아무것도 보내지 않고 409이며 주문은 그대로다. */
    @Test
    fun `reconciling a draft returns 409 PAYMENT_NOT_STARTED and sends nothing to the PG`() {
        prepareProduct(stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        val error = assertThat(requestReconcile(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("PAYMENT_NOT_STARTED")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.requestCount()).isZero()
    }

    /** 결제가 있는 주문은 결제 중이든 끝났든 결제를 시작한 키로만 다시 물을 수 있다. 다른 키는 409이고 바뀌는 것이 없다(ADR 0025 규칙 3). */
    @ParameterizedTest
    @CsvSource("SUCCESS, OK, 8", "PENDING, ACCEPTED, 8", "FAILED, OK, 10")
    fun `confirming an order that has a payment with another key returns 409 IDEMPOTENCY_KEY_MISMATCH and changes nothing`(
        pgStatus: String,
        firstStatus: HttpStatus,
        stock: Int,
    ) {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        acceptCreate(amount = 2_000, pgStatus = pgStatus)
        assertThat(requestConfirm(orderId, idempotencyKey = "first-key")).hasStatus(firstStatus)
        val paidDetail = requestDetail(orderId)
        assertThat(paidDetail).hasStatusOk()

        val error = assertThat(requestConfirm(orderId, idempotencyKey = "second-key")).hasStatus(HttpStatus.CONFLICT).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("IDEMPOTENCY_KEY_MISMATCH")

        assertThat(requestDetail(orderId).json()).isEqualTo(paidDetail.json())
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isZero()
        assertStock(product, stock)
    }

    /**
     * 키는 모든 사용자에 걸쳐 유일하므로 다른 사용자의 주문이 쓴 키도 쓸 수 없다(ADR 0025 규칙 2, 3). 재고를 예약하기 전에 거절하므로 확정 전 주문과
     * 재고가 그대로이고, 재고가 모자란 주문(재고 1)에서도 재고 부족보다 이 거절이 앞선다.
     */
    @ParameterizedTest
    @ValueSource(ints = [10, 1])
    fun `confirming a draft with a key another user's order used returns 409 IDEMPOTENCY_KEY_IN_USE before reserving`(
        stock: Int,
    ) {
        prepareBrand()
        val paidProduct = prepareProduct(brand, price = 1_000, stock = 10)
        val paidId = prepareOrder(owner, listOf(paidProduct), quantity = 1).id
        acceptCreate(amount = 1_000)
        assertThat(requestConfirm(paidId, idempotencyKey = "shared-key")).hasStatusOk()
        val other = prepareUser()
        val draftProduct = prepareProduct(brand, price = 1_000, stock = stock)
        val draftId = prepareOrder(other, listOf(draftProduct), quantity = 2).id
        val draft = requestDetail(draftId, other.id)
        assertThat(draft).hasStatusOk()

        val error = assertThat(requestConfirm(draftId, other.id, "shared-key")).hasStatus(HttpStatus.CONFLICT).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("IDEMPOTENCY_KEY_IN_USE")

        assertThat(requestDetail(draftId, other.id).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isOne()
        assertStock(draftProduct, stock)
    }

    @Test
    fun `confirming without an Idempotency-Key returns 400 before anything is reserved or sent`() {
        prepareProduct(stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        val error = assertThat(requestConfirm(orderId, idempotencyKey = null)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        error.extractingPath("$.meta.message").isEqualTo("필수 요청 헤더 'Idempotency-Key'가 누락되었습니다.")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        assertStock(product, 10)
    }

    /**
     * 웹 경계가 결제의 키 규칙으로 먼저 거절한다(ADR 0025 규칙 2). 결제의 생성도 같은 규칙을 검사하므로, 주문을 읽기도 전에 거절했는지는
     * 없는 주문에도 404가 아니라 400인 것으로 본다. 64자 키가 받아들여지는 것은 `PaymentTest`가 본다.
     */
    @ParameterizedTest
    @MethodSource("malformedKeys")
    fun `confirming with a malformed Idempotency-Key returns 400 naming the broken rule before anything is reserved or sent`(
        key: String,
        message: String,
    ) {
        prepareProduct(stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        val error = assertThat(requestConfirm(orderId, idempotencyKey = key)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        error.extractingPath("$.meta.message").isEqualTo(message)

        assertThat(requestConfirm(Long.MAX_VALUE, idempotencyKey = key)).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        assertStock(product, 10)
    }

    /**
     * 카드는 PG 포트와 같은 제약으로 TX A 전에 검사한다. 포트가 TX A 뒤에 처음 거절하면 보내지 않을 결제가 예약을 영영 쥔다(ADR 0024 규칙 1).
     * 셋째 카드 번호는 Luhn 검사를 지나는 번호의 마지막 숫자만 바꿨다.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"cardType":"","cardNo":"4111-1111-1111-1111"}""",
            """{"cardType":"SAMSUNG","cardNo":" "}""",
            """{"cardType":"SAMSUNG","cardNo":"4111-1111-1111-1112"}""",
            """{"cardType":"SAMSUNG"}""",
        ],
    )
    fun `confirming with an invalid card returns 400 before anything is reserved or sent`(json: String) {
        prepareProduct(stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        val error = assertThat(requestConfirm(orderId, json = json)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        assertStock(product, 10)
    }

    /**
     * PG를 부르기 전의 거절은 결제를 만들지 않으므로 키도 쓰이지 않는다. 같은 키로 다시 확정할 수 있다(ADR 0025 규칙 3).
     * 품목의 재고 예약은 모두 함께 되돌아간다.
     */
    @Test
    fun `a later item shortage rolls back every reservation and sends nothing, and the same key confirms after replenishment`() {
        prepareBrand()
        val first = prepareProduct(brand, price = 1_000, stock = 10)
        val second = prepareProduct(brand, price = 1_000, stock = 4)
        val orderId = prepareOrder(first to 2, second to 5, user = owner).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        val error = assertThat(requestConfirm(orderId, idempotencyKey = "retry-key")).hasStatus(HttpStatus.CONFLICT).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("INSUFFICIENT_STOCK")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        assertStock(first, 10)
        assertStock(second, 4)
        updateProductStock(quantity = 5, product = second)
        acceptCreate(amount = 7_000)
        val body = assertThat(requestConfirm(orderId, idempotencyKey = "retry-key")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.paidAmount").isEqualTo(7_000)
        assertThat(testPg.createCount()).isOne()
        assertStock(first, 8)
        assertStock(second, 0)
    }

    /** 생성 스텁은 금액까지 맞추므로, 확정이 바뀐 카탈로그 가격으로 PG에 요청했다면 스텁과 맞지 않아 200이 나오지 않는다(ADR 0002). */
    @ParameterizedTest
    @ValueSource(longs = [500, 2_000])
    fun `an old draft is charged its saved price after a catalog price increase or decrease`(newPrice: Long) {
        prepareProduct(name = "원래 이름", price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        jdbc.update("update orders set created_at = '2020-01-01 00:00:00.123456' where id = ?", orderId)
        updateProduct(name = "바뀐 이름", price = newPrice)
        acceptCreate(amount = 1_000)

        val confirmed = requestConfirm(orderId)
        val body = assertThat(confirmed).hasStatusOk().bodyJson()
        body.extractingPath("$.data.paidAmount").isEqualTo(1_000)
        body.extractingPath("$.data.items[0].productName").isEqualTo("원래 이름")
        body.extractingPath("$.data.items[0].unitPrice").isEqualTo(1_000)
        body.extractingPath("$.data.createdAt").isEqualTo("2020-01-01T00:00:00.123456Z")

        assertThat(requestDetail(orderId).json()).isEqualTo(confirmed.json())
        assertThat(testPg.createCount()).isOne()
        assertStock(product, 9)
    }

    /**
     * PG는 금액을 64비트 정수로 받으므로 그보다 큰 주문은 TX A 안에서 결제를 만들기 전에 거절된다. 예약과 함께 되돌아가므로 주문은 확정 전
     * 그대로이고 PG는 불리지 않는다. 다섯 품목의 합계는 10,737,418,235,000,000,000원이다.
     */
    @Test
    fun `confirming an order above Long MAX_VALUE is rejected with 400 before the PG and keeps the draft and the stock`() {
        prepareBrand()
        val products = List(5) { prepareProduct(brand, price = 1_000_000_000, stock = Int.MAX_VALUE) }
        val orderId = prepareOrder(owner, products, quantity = Int.MAX_VALUE).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        val error = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.message").isEqualTo("결제 금액은 ${Long.MAX_VALUE}원 이하여야 합니다.")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        products.forEach { assertStock(it, Int.MAX_VALUE) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["product", "brand"])
    fun `unavailable products or brands reject the whole confirmation before the PG and preserve the draft`(deleted: String) {
        val first = prepareProduct(stock = 10)
        val secondBrand = prepareBrand()
        val second = prepareProduct(secondBrand, stock = 10)
        val orderId = prepareOrder(owner, listOf(first, second)).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        if (deleted == "product") {
            deleteProduct(second)
        } else {
            deleteBrand(secondBrand)
        }

        val error = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("ORDER_PRODUCT_NOT_AVAILABLE")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        assertStock(first, 10)
        assertStock(second, 10)
    }

    /**
     * 품목은 상품 ID 오름차순이다. 판매 불가와 재고 부족을 함께 두고 둘의 차례를 바꿔 넣어, 응답하는 오류가
     * 품목의 차례가 아니라 확정의 검사 차례로 정해지는지 본다(설계 15).
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an unavailable product outranks a shortage whichever item comes first`(shortageFirst: Boolean) {
        prepareBrand()
        val first = prepareProduct(brand)
        val second = prepareProduct(brand)
        val orderId = prepareOrder(owner, listOf(first, second), quantity = 2).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        val (short, unavailable) = if (shortageFirst) first to second else second to first
        updateProductStock(quantity = 1, product = short)
        deleteProduct(unavailable)

        val error = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("ORDER_PRODUCT_NOT_AVAILABLE")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        assertStock(short, 1)
    }

    /** 주인의 키를 실어도 요청자와 소유를 먼저 본다. 남의 주문은 결제가 있어도 재요청이 아니라 없는 주문과 같은 404다. */
    @Test
    fun `requester and ownership checks precede both the first confirmation and a replay of its key`() {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        val otherUser = prepareUser().id
        val key = "owner-key"

        fun assertAccessDenied() {
            listOf(null, Long.MAX_VALUE).forEach { requester ->
                val body = assertThat(requestConfirm(orderId, requester, key)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
                body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
            }
            val missing = requestConfirm(Long.MAX_VALUE, idempotencyKey = key)
            val error = assertThat(missing).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
            error.extractingPath("$.meta.errorCode").isEqualTo("ORDER_NOT_FOUND")
            val forbidden = requestConfirm(orderId, otherUser, key)
            assertThat(forbidden).hasStatus(HttpStatus.NOT_FOUND)
            assertThat(forbidden.json()).isEqualTo(missing.json())
        }

        assertAccessDenied()
        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        assertStock(product, 10)
        acceptCreate(amount = 1_000)
        val confirmed = requestConfirm(orderId, idempotencyKey = key)
        assertThat(confirmed).hasStatusOk()
        assertAccessDenied()
        assertThat(requestConfirm(orderId, idempotencyKey = key).json()).isEqualTo(confirmed.json())
        assertThat(requestDetail(orderId).json()).isEqualTo(confirmed.json())
        assertThat(testPg.createCount()).isOne()
        assertStock(product, 9)
    }

    /**
     * 결제가 있는 주문의 재요청과 키 불일치는 상품·재고보다 먼저 본다. 재고를 다 쓰고 상품·브랜드가 삭제돼도 판매 불가가 아니라, 같은 키는 저장된
     * 상태로 답하고 다른 키는 409다. 재고가 0인 상품은 브랜드 삭제가 함께 삭제하고(ADR 0017), 확정된 주문은 품목, 수량, 단가, 합계, 결제 결과까지
     * 그대로 읽힌다.
     */
    @Test
    fun `a paid order replays its key and rejects another key even after its product and brand are deleted`() {
        prepareProduct(price = 1_000, stock = 3)
        val firstId = prepareOrder(owner, listOf(product), quantity = 1).id
        acceptCreate(amount = 1_000)
        val confirmed = requestConfirm(firstId, idempotencyKey = "first-key")
        assertThat(confirmed).hasStatusOk()
        val secondId = prepareOrder(owner, listOf(product), quantity = 2).id
        acceptCreate(amount = 2_000)
        assertThat(requestConfirm(secondId)).hasStatusOk()
        assertStock(product, 0)
        deleteBrand()

        repeat(2) {
            val replayed = requestConfirm(firstId, idempotencyKey = "first-key")
            assertThat(replayed).hasStatusOk()
            assertThat(replayed.json()).isEqualTo(confirmed.json())
            assertKeyMismatch(firstId)
            val firstDetail = requestDetail(firstId)
            assertThat(firstDetail).hasStatusOk()
            assertThat(firstDetail.json()).isEqualTo(confirmed.json())
        }

        assertThat(testPg.createCount()).isEqualTo(2)
        assertStock(product, 0)
    }

    /**
     * PG 결제가 생기기 전에 포인트로 확정된 주문은 결제수단 POINT로 읽히고 결제 상태가 없다. 결제가 없으므로 상세는 결제를 읽지 않는다.
     * 다시 확정하면 409다(과제 3절). 그런 주문은 이제 만들 길이 없어 준비한 초안 위에 SQL로 덮어쓴다(설계 13).
     */
    @Test
    fun `an old point-paid order reads back without a payment status and re-confirming it is rejected without a create`() {
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        jdbc.update(
            "update orders set status = 'CONFIRMED', payment_method = 'POINT', confirmed_at = now(6) where id = ?",
            orderId,
        )

        val body = assertThat(requestDetail(orderId)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("CONFIRMED")
        body.extractingPath("$.data.paymentMethod").isEqualTo("POINT")
        body.extractingPath("$.data.paidAmount").isEqualTo(2_000)
        body.doesNotHavePath("$.data.paymentStatus")

        assertAlreadyConfirmed(orderId)
        assertThat(testPg.createCount()).isZero()
        assertStock(product, 10)
    }

    /**
     * TX A의 UPDATE는 대부분 커밋의 flush에서 나가고, `order_updates`가 그것을 엔티티 이름 순으로 보낸다. 주문, 상품 차례다(ADR 0018).
     * 결제의 INSERT는 식별자가 `IDENTITY`라 저장에서 그보다 먼저 나갔다. 그래서 커밋의 마지막 UPDATE는 둘째 상품의 재고다. 그것만 임시
     * CHECK로 거절해, 앞서 나간 재고·결제·주문의 쓰기까지 함께 되돌아가는지 본다(설계 18.2). 발송 기록이 커밋되지 않았으므로 PG에는
     * 아무것도 가지 않는다(ADR 0024 규칙 2). 실패한 확정의 결제가 PG 주문 번호를 하나 가져가므로, 다시 확정한 결제는 다음 번호를 받는다.
     */
    @Test
    fun `a failure on TX A's last write at commit undoes its reservations and payment, sends nothing and permits retry`() {
        prepareBrand()
        val first = prepareProduct(brand, price = 1_000, stock = 6)
        val second = prepareProduct(brand, price = 2_000, stock = 3)
        val orderId = prepareOrder(first to 5, second to 1, user = owner).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        nextPgOrderId()
        acceptCreate(amount = 7_000)
        jdbc.execute("alter table product add constraint fail_second_stock check (stock <> 2)")
        try {
            assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        } finally {
            jdbc.execute("alter table product drop check fail_second_stock")
        }

        // A new transaction, outside the failed HTTP request, proves rollback rather than test cleanup.
        transaction.executeWithoutResult {
            assertStock(first, 6)
            assertStock(second, 3)
            assertThat(jdbc.queryForObject("select count(*) from payment where order_id = ?", Long::class.java, orderId)!!)
                .isZero()
            assertThat(jdbc.queryForMap("select status, payment_method, confirmed_at from orders where id = ?", orderId))
                .containsAllEntriesOf(mapOf("status" to "DRAFT", "payment_method" to null, "confirmed_at" to null))
        }
        val afterRollback = requestDetail(orderId)
        assertThat(afterRollback).hasStatusOk()
        assertThat(afterRollback.json()).isEqualTo(draft.json())
        assertThat(testPg.createCount()).isZero()
        assertThat(requestConfirm(orderId)).hasStatusOk()
        assertThat(testPg.createCount()).isOne()
        assertStock(first, 1)
        assertStock(second, 2)
    }

    /** 포인트로 확정된 주문은 다시 확정하면 예약도 생성도 없이 거절된다. 그 결과는 GET으로 읽는다. */
    private fun assertAlreadyConfirmed(orderId: Long) {
        val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("ORDER_ALREADY_CONFIRMED")
    }

    /** 결제가 있는 주문은 새 키로 다시 확정하면 예약도 생성도 없이 거절된다. 첫 확정의 결과는 GET이나 같은 키로 읽는다. */
    private fun assertKeyMismatch(orderId: Long) {
        val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("IDEMPOTENCY_KEY_MISMATCH")
    }

    /** 다음 결제의 [amount]원 생성을 [CARD]로 받아들여 PG 상태 [pgStatus]의 PG 거래로 답한다. */
    private fun acceptCreate(amount: Long, pgStatus: String = "SUCCESS") {
        acceptNextCreate(testPg, amount = amount, card = CARD, pgStatus = pgStatus)
    }

    private fun assertStock(product: Product, expected: Int) {
        assertThat(jdbc.queryForObject("select stock from product where id = ?", Int::class.java, product.id)!!)
            .isEqualTo(expected)
    }

    /** 멱등 키는 확정마다 새로 만든다. `null`이면 헤더를 싣지 않는다. 본문은 [CARD]를 싣는다. */
    private fun requestConfirm(
        orderId: Long,
        requester: Long? = owner.id,
        idempotencyKey: String? = UUID.randomUUID().toString(),
        json: String = """{"cardType":"${CARD.type}","cardNo":"${CARD.number}"}""",
    ): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .apply { requester?.let { header(UserIdHeader.NAME, it) } }
            .apply { idempotencyKey?.let { header(IdempotencyKeyHeader.NAME, it) } }
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()
    }

    /** 본문 없이 대사한다. `null`이면 요청자 헤더를 싣지 않는다. */
    private fun requestReconcile(orderId: Long, requester: Long? = owner.id): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/payment/reconcile")
            .apply { requester?.let { header(UserIdHeader.NAME, it) } }
            .exchange()
    }

    private fun requestDetail(orderId: Long, requester: Long = owner.id): MvcTestResult {
        return mvc.get().uri("/api/v1/orders/$orderId").header(UserIdHeader.NAME, requester).exchange()
    }

    private fun MvcTestResult.json(): JsonNode {
        return objectMapper.readTree(response.contentAsString)
    }
}
