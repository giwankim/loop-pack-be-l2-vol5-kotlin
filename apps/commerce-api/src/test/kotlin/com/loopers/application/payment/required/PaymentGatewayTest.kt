package com.loopers.application.payment.required

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.pg.LocalPgGatewayConfig
import com.loopers.domain.payment.Card
import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.TestPg.Companion.REJECTED_BEFORE_CREATE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.wiremock.spring.InjectWireMock

/** test 프로필과 다른 알림 주소. 생성 요청에 이 값이 실리면 어댑터가 알림 주소를 설정에서 읽은 것이다. */
private const val CALLBACK_URL = "http://127.0.0.1:9090/api/v1/payments/callback"

/**
 * [PaymentGateway]를 실제 HTTP로 테스트 PG에 보낸다(ADR 0022 규칙 6). 포트의 인터페이스만 부르고, 포트가 돌려준 값과 테스트 PG가 받은 요청만 본다.
 *
 * 컨텍스트에는 어댑터의 설정만 있고 MySQL과 Redis는 없다. 설정 값은 `application.yml`을 test 프로필로 읽고 둘만 바꾼다.
 * - 응답 한도는 운영의 1 s다. PG가 받은 요청 수로 숨은 재전송이 꺼졌음을 보는 테스트는 운영의 시간 한도로 돈다(ADR 0022 규칙 6).
 *   HttpClient는 응답 한도가 재시도 간격 1 s보다 짧으면 503 재시도를 스스로 건너뛰므로, test 프로필의 200 ms에서는 재시도를 끄는 스위치를
 *   지워도 요청 수가 그대로다(탐침 보고서 §3).
 * - 알림 주소는 [CALLBACK_URL]이다.
 *
 * 테스트 PG를 쓰는 다른 테스트와 달리 기반 클래스를 상속하지 않는다. 호출마다의 로그 줄은 출력 캡처로 읽는다. 테스트가 끝날 때마다
 * 테스트 PG가 받은 요청이 모두 장면의 스텁과 맞았는지 본다([everyRequestMatchedAScene]).
 * 연결 풀을 얻지 못한 생성은 풀의 연결 수가 다른 컨텍스트가 필요해 [ExhaustedPoolPaymentGatewayTest]에 있다.
 */
@SpringJUnitConfig(classes = [LocalPgGatewayConfig::class], initializers = [ConfigDataApplicationContextInitializer::class])
@TestPropertySource(properties = ["pg.response-timeout=1s", "pg.callback-url=$CALLBACK_URL"])
@EnableTestPg
@ExtendWith(OutputCaptureExtension::class)
class PaymentGatewayTest(
    private val paymentGateway: PaymentGateway,
) {
    companion object {
        private const val PG_ORDER_ID = "LP-ORD-0000000042"
        private const val AMOUNT = 7_000L
        private val CARD = Card(type = "TEST", number = "0000-0000-0000-0000")

        /**
         * 생성 전 거절을 닮았지만 정확하지 않은 422의 본문. `null`은 본문 없는 422다. 어느 것도 PG 거래가 없다는 증거가 아니다(ADR 0022 규칙 3).
         * `transactionCreated`는 JSON boolean `false`만 증거다. 빠지거나 `null`인 값을 `false`로 읽으면 안 된다.
         */
        @JvmStatic
        fun inexactRejections(): List<String?> {
            return listOf(
                null,
                """{"code":"TEST_PG_REJECTED","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"transactionCreated":false}""",
                """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"transactionCreated":true}""",
                """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"transactionCreated":"false"}""",
                """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"transactionCreated":null}""",
                """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$PG_ORDER_ID","amount":$AMOUNT}""",
                """{"code":"$REJECTED_BEFORE_CREATE","amount":$AMOUNT,"transactionCreated":false}""",
                """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$PG_ORDER_ID","transactionCreated":false}""",
                """{"orderId":"$PG_ORDER_ID","amount":$AMOUNT,"transactionCreated":false}""",
                """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$PG_ORDER_ID","amount":"$AMOUNT","transactionCreated":false}""",
                """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$PG_ORDER_ID",""",
            )
        }

        /** 계약대로 읽을 수 있는 PG 거래 하나. 아래 모양들이 이것과 한 군데씩 다르다. */
        private const val WELL_FORMED_TRANSACTION =
            """{"transactionKey":"TEST-TX-0042","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}"""

        /**
         * 계약대로 읽을 수 없는 PG 거래. 생성의 202에도 두 조회의 200에도 같은 것들을 싣는다. 잘린 JSON, 문자열 금액, 빠진 거래 키나 금액,
         * 모르는 PG 상태, 빈 거래 키다. 빠진 금액은 원시 타입이라 너그러운 매퍼라면 0원으로 읽어 잘 짜인 거래가 된다. 거래 키가 빈 거래는
         * 나중에 그 키로 조회할 수 없으므로 잘 짜인 거래가 아니다.
         */
        private val MALFORMED_TRANSACTIONS = listOf(
            """{"transactionKey":"TEST-TX-0042","orderId":"$PG_ORDER_ID",""",
            """{"transactionKey":"TEST-TX-0042","orderId":"$PG_ORDER_ID","amount":"$AMOUNT","status":"SUCCESS"}""",
            """{"orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}""",
            """{"transactionKey":"TEST-TX-0042","orderId":"$PG_ORDER_ID","status":"SUCCESS"}""",
            """{"transactionKey":"TEST-TX-0042","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"CANCELLED"}""",
            """{"transactionKey":"","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}""",
            """{"transactionKey":"   ","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}""",
        )

        /** 202에 실렸지만 잘 짜인 PG 거래가 아닌 본문. */
        @JvmStatic
        fun unreadableTransactions(): List<String> {
            return MALFORMED_TRANSACTIONS
        }

        /** 거래 키 조회의 200에 실려 와도 PG 거래로 읽지 않는 본문. 읽을 수 없는 거래, 빈 본문, 객체 대신 배열이다. */
        @JvmStatic
        fun unreadableTransactionBodies(): List<String> {
            return MALFORMED_TRANSACTIONS + listOf("", "[$WELL_FORMED_TRANSACTION]")
        }

        /** 목록 조회의 200에 실려 와도 PG 거래의 배열로 읽지 않는 본문. 읽을 수 없는 거래를 담은 배열, 배열 대신 객체, null 원소, null이다. */
        @JvmStatic
        fun unreadableListBodies(): List<String> {
            return MALFORMED_TRANSACTIONS.map { "[$it]" } + listOf(WELL_FORMED_TRANSACTION, "[null]", "null")
        }
    }

    /**
     * 생성자 주입의 하나뿐인 예외다. `junit-platform.properties`의 `spring.test.constructor.autowire.mode=all` 때문에 생성자 파라미터는
     * Spring과 WireMock의 파라미터 resolver가 함께 맡으려 다투다 실패한다(탐침 보고서 §7의 1). 필드는 WireMock의 리스너가 넣는다.
     */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val pg by lazy { TestPg(server, callbackUrl = CALLBACK_URL) }

    /** 테스트 PG가 받은 요청은 모두 장면의 스텁과 맞았어야 한다. 조회에서는 스텁을 찾지 못한 404만으로 테스트가 실패하지 않는다([TestPg]). */
    @AfterEach
    fun everyRequestMatchedAScene() {
        assertThat(pg.unmatchedRequests()).isEmpty()
    }

    @Test
    fun `the test PG listens on the loopback address only and without h2c`() {
        assertThat(server.options.bindAddress()).isEqualTo("127.0.0.1")
        assertThat(server.options.http2PlainDisabled).isTrue()
    }

    @Test
    fun `an accepted create returns the PG's transaction and reaches the PG as one POST`() {
        pg.acceptsCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, transactionKey = "TEST-TX-0042")

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isEqualTo(
            PaymentRequestResult.Accepted(PgTransaction("TEST-TX-0042", PG_ORDER_ID, Money(AMOUNT), PgStatus.PENDING)),
        )
        assertThat(pg.createCount()).isEqualTo(1)
    }

    @ParameterizedTest
    @EnumSource(PgStatus::class)
    fun `a 202 comes back as Accepted whatever PG status the transaction carries`(status: PgStatus) {
        pg.acceptsCreate(
            pgOrderId = PG_ORDER_ID,
            amount = AMOUNT,
            card = CARD,
            transactionKey = "TEST-TX-0042",
            pgStatus = status.name,
        )

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isEqualTo(
            PaymentRequestResult.Accepted(PgTransaction("TEST-TX-0042", PG_ORDER_ID, Money(AMOUNT), status)),
        )
    }

    @Test
    fun `an exact pre-create rejection comes back as Rejected and reaches the PG as one POST`() {
        pg.rejectsBeforeCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD)

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isEqualTo(PaymentRequestResult.Rejected(PgRejection(PG_ORDER_ID, Money(AMOUNT))))
        assertThat(pg.createCount()).isEqualTo(1)
    }

    /** 되돌려 받은 값이 보낸 것과 같은지는 결제가 본다(ADR 0022 규칙 2). 어댑터는 PG가 말한 그대로 옮긴다. */
    @Test
    fun `a pre-create rejection carries the order number and amount the PG echoed without comparing them`() {
        pg.answersCreate(
            pgOrderId = PG_ORDER_ID,
            amount = AMOUNT,
            card = CARD,
            httpStatus = 422,
            body = """
                {"code":"$REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000043","amount":7001,"transactionCreated":false}
            """,
        )

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isEqualTo(PaymentRequestResult.Rejected(PgRejection("LP-ORD-0000000043", Money(7_001))))
    }

    @ParameterizedTest
    @MethodSource("inexactRejections")
    fun `a 422 that is not the exact pre-create rejection comes back as Unknown`(body: String?) {
        pg.answersCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, httpStatus = 422, body = body)

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isInstanceOf(PaymentRequestResult.Unknown::class.java)
        assertThat(pg.createCount()).isEqualTo(1)
    }

    /**
     * HttpClient의 기본값은 429와 503에 POST를 한 번 더 보낸다(ADR 0022 규칙 5). 503은 응답 한도가 재시도 간격 1 s 이상일 때만 다시 보내므로
     * 이 클래스가 운영의 응답 한도로 돌아야 이 수가 재시도를 끄는 스위치를 지킨다.
     */
    @ParameterizedTest
    @ValueSource(ints = [400, 404, 409, 429, 500, 502, 503, 504])
    fun `any other 4xx or 5xx comes back as Unknown instead of being thrown and reaches the PG as one POST`(httpStatus: Int) {
        pg.answersCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, httpStatus = httpStatus)

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isEqualTo(PaymentRequestResult.Unknown("HTTP $httpStatus"))
        assertThat(pg.createCount()).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 201])
    fun `a 2xx other than 202 comes back as Unknown even with a well-formed transaction`(httpStatus: Int) {
        pg.answersCreate(
            pgOrderId = PG_ORDER_ID,
            amount = AMOUNT,
            card = CARD,
            httpStatus = httpStatus,
            body = """{"transactionKey":"TEST-TX-0042","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}""",
        )

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isEqualTo(PaymentRequestResult.Unknown("HTTP $httpStatus"))
    }

    @ParameterizedTest
    @MethodSource("unreadableTransactions")
    fun `a 202 whose body is not a well-formed transaction comes back as Unknown`(body: String) {
        pg.answersCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, httpStatus = 202, body = body)

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isInstanceOf(PaymentRequestResult.Unknown::class.java)
        assertThat(pg.createCount()).isEqualTo(1)
    }

    /** HttpClient의 기본값은 307에 본문까지 복사해 리다이렉트를 따라간다(ADR 0022 규칙 5). */
    @Test
    fun `a 307 comes back as Unknown and the PG receives one POST and nothing at the redirect target`() {
        pg.redirectsCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD)

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isEqualTo(PaymentRequestResult.Unknown("HTTP 307"))
        assertThat(pg.createCount()).isEqualTo(1)
        assertThat(pg.requestCount()).isEqualTo(1)
    }

    @Test
    fun `a connection dropped after the PG stored the transaction comes back as Unknown after one POST`() {
        pg.losesCreateResponse(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD)

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isInstanceOf(PaymentRequestResult.Unknown::class.java)
        assertThat(pg.createCount()).isEqualTo(1)
    }

    /** 이 클래스의 하나뿐인 느린 장면이다. 테스트 PG는 1.5 s 멈추고 어댑터는 응답 한도 1 s에서 그만 기다린다. */
    @Test
    fun `a create stalled past the response timeout comes back as Unknown after one POST`() {
        pg.stalls(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, transactionKey = "TEST-TX-0042")

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isInstanceOf(PaymentRequestResult.Unknown::class.java)
        assertThat(pg.createCount()).isEqualTo(1)
    }

    @Test
    fun `an accepted create writes one log line with the call and no body or card number`(output: CapturedOutput) {
        pg.acceptsCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, transactionKey = "TEST-TX-0042")

        paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(output.gatewayLines()).singleElement().asString()
            .contains("POST /api/v1/payments", "HTTP 202", PG_ORDER_ID, "TEST-TX-0042")
            .containsPattern("in \\d+ ms")
            .doesNotContain(CARD.number, "cardNo", "callbackUrl")
    }

    @Test
    fun `an unknown create writes one log line with the HTTP status and no card number`(output: CapturedOutput) {
        pg.answersCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, httpStatus = 500)

        paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(output.gatewayLines()).singleElement().asString()
            .contains("POST /api/v1/payments", "HTTP 500", PG_ORDER_ID)
            .containsPattern("in \\d+ ms")
            .doesNotContain(CARD.number, "cardNo", "callbackUrl")
    }

    @Test
    fun `a key lookup answered 200 returns that one transaction and reaches the PG as one GET`() {
        val transaction = PgTransaction("TEST-TX-0042", PG_ORDER_ID, Money(AMOUNT), PgStatus.SUCCESS)
        pg.hasTransaction(transaction)

        val result = paymentGateway.retrieveTransaction("TEST-TX-0042")

        assertThat(result).isEqualTo(TransactionLookupResult.Found(listOf(transaction)))
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @Test
    fun `a key lookup answered 404 returns no transaction instead of being unavailable`() {
        pg.answersRetrieve(transactionKey = "TEST-TX-0042", httpStatus = 404)

        val result = paymentGateway.retrieveTransaction("TEST-TX-0042")

        assertThat(result).isEqualTo(TransactionLookupResult.Found(emptyList()))
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @Test
    fun `a list lookup answered with an empty array returns no transaction instead of being unavailable`() {
        pg.hasTransactions(pgOrderId = PG_ORDER_ID)

        val result = paymentGateway.listTransactions(PG_ORDER_ID)

        assertThat(result).isEqualTo(TransactionLookupResult.Found(emptyList()))
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @Test
    fun `a list lookup answered with one transaction returns that transaction`() {
        val transaction = PgTransaction("TEST-TX-0042", PG_ORDER_ID, Money(AMOUNT), PgStatus.PENDING)
        pg.hasTransactions(pgOrderId = PG_ORDER_ID, transaction)

        val result = paymentGateway.listTransactions(PG_ORDER_ID)

        assertThat(result).isEqualTo(TransactionLookupResult.Found(listOf(transaction)))
    }

    @Test
    fun `a list lookup answered with two transactions returns both and reaches the PG as one GET`() {
        val failed = PgTransaction("TEST-TX-0041", PG_ORDER_ID, Money(AMOUNT), PgStatus.FAILED)
        val succeeded = PgTransaction("TEST-TX-0042", PG_ORDER_ID, Money(AMOUNT), PgStatus.SUCCESS)
        pg.hasTransactions(pgOrderId = PG_ORDER_ID, failed, succeeded)

        val result = paymentGateway.listTransactions(PG_ORDER_ID)

        assertThat(result).isEqualTo(TransactionLookupResult.Found(listOf(failed, succeeded)))
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    /**
     * PG 주문 번호는 URI builder의 query 변수라 `+`와 `&`까지 인코딩된다. 값을 query에 그대로 이어 붙이면 `&`가 query를 둘로 가르고,
     * PG는 `+`를 공백으로 읽는다. 어느 쪽이든 스텁이 정확히 맞추는 `orderId`와 다르다.
     */
    @Test
    fun `a list lookup sends the PG order number as one exact orderId query parameter even with reserved characters`() {
        val pgOrderId = "LP-ORD+42&orderId=43"
        pg.hasTransactions(pgOrderId = pgOrderId)

        val result = paymentGateway.listTransactions(pgOrderId)

        assertThat(result).isEqualTo(TransactionLookupResult.Found(emptyList()))
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    /** HttpClient의 기본 재시도는 끊긴 GET을 한 번 더 보낸다(탐침 보고서 §3). 조회의 재시도는 3단계가 명시적으로 정한다. */
    @Test
    fun `a lookup whose connection drops comes back as Unavailable after exactly one GET`() {
        pg.losesLookupResponse()

        val result = paymentGateway.retrieveTransaction("TEST-TX-0042")

        assertThat(result).isEqualTo(TransactionLookupResult.Unavailable("NoHttpResponseException"))
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @Test
    fun `a lookup that outlasts the response timeout comes back as Unavailable after exactly one GET`() {
        pg.stallsLookup()

        val result = paymentGateway.listTransactions(PG_ORDER_ID)

        assertThat(result).isEqualTo(TransactionLookupResult.Unavailable("SocketTimeoutException"))
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [201, 204, 400, 500, 503])
    fun `a key lookup answered with a status other than 200 or 404 comes back as Unavailable instead of being thrown`(
        httpStatus: Int,
    ) {
        pg.answersRetrieve(transactionKey = "TEST-TX-0042", httpStatus = httpStatus)

        val result = paymentGateway.retrieveTransaction("TEST-TX-0042")

        assertThat(result).isEqualTo(TransactionLookupResult.Unavailable("HTTP $httpStatus"))
    }

    @ParameterizedTest
    @ValueSource(ints = [201, 204, 400, 404, 500, 503])
    fun `a list lookup answered with a status other than 200, 404 included, comes back as Unavailable`(httpStatus: Int) {
        pg.answersList(pgOrderId = PG_ORDER_ID, httpStatus = httpStatus)

        val result = paymentGateway.listTransactions(PG_ORDER_ID)

        assertThat(result).isEqualTo(TransactionLookupResult.Unavailable("HTTP $httpStatus"))
    }

    @ParameterizedTest
    @MethodSource("unreadableTransactionBodies")
    fun `a key lookup answered 200 with an unreadable body comes back as Unavailable instead of being thrown`(json: String) {
        pg.answersRetrieve(transactionKey = "TEST-TX-0042", httpStatus = 200, body = json)

        val result = paymentGateway.retrieveTransaction("TEST-TX-0042")

        assertThat(result).isInstanceOf(TransactionLookupResult.Unavailable::class.java)
    }

    @ParameterizedTest
    @MethodSource("unreadableListBodies")
    fun `a list lookup answered 200 with an unreadable body comes back as Unavailable instead of being thrown`(json: String) {
        pg.answersList(pgOrderId = PG_ORDER_ID, httpStatus = 200, body = json)

        val result = paymentGateway.listTransactions(PG_ORDER_ID)

        assertThat(result).isInstanceOf(TransactionLookupResult.Unavailable::class.java)
    }

    @Test
    fun `a key lookup writes one log line with the call, the status and the transaction`(output: CapturedOutput) {
        pg.hasTransaction(PgTransaction("TEST-TX-0042", PG_ORDER_ID, Money(AMOUNT), PgStatus.SUCCESS))

        paymentGateway.retrieveTransaction("TEST-TX-0042")

        assertThat(output.gatewayLines()).singleElement().asString()
            .contains("GET /api/v1/payments/{transactionKey}", "Found 1", "HTTP 200", PG_ORDER_ID, "TEST-TX-0042")
            .containsPattern("in \\d+ ms")
    }

    @Test
    fun `a key lookup that finds none writes one log line with HTTP 404`(output: CapturedOutput) {
        pg.answersRetrieve(transactionKey = "TEST-TX-0042", httpStatus = 404)

        paymentGateway.retrieveTransaction("TEST-TX-0042")

        assertThat(output.gatewayLines()).singleElement().asString()
            .contains("GET /api/v1/payments/{transactionKey}", "Found 0", "HTTP 404", "TEST-TX-0042")
    }

    @Test
    fun `a list lookup writes one log line with the call and every transaction key`(output: CapturedOutput) {
        pg.hasTransactions(
            pgOrderId = PG_ORDER_ID,
            PgTransaction("TEST-TX-0041", PG_ORDER_ID, Money(AMOUNT), PgStatus.FAILED),
            PgTransaction("TEST-TX-0042", PG_ORDER_ID, Money(AMOUNT), PgStatus.SUCCESS),
        )

        paymentGateway.listTransactions(PG_ORDER_ID)

        assertThat(output.gatewayLines()).singleElement().asString()
            .contains("GET /api/v1/payments?orderId={pgOrderId}", "Found 2", "HTTP 200", PG_ORDER_ID)
            .contains("TEST-TX-0041", "TEST-TX-0042")
            .containsPattern("in \\d+ ms")
    }

    @Test
    fun `an unavailable lookup writes one log line with the HTTP status`(output: CapturedOutput) {
        pg.answersList(pgOrderId = PG_ORDER_ID, httpStatus = 500)

        paymentGateway.listTransactions(PG_ORDER_ID)

        assertThat(output.gatewayLines()).singleElement().asString()
            .contains("GET /api/v1/payments?orderId={pgOrderId}", "Unavailable", "HTTP 500", PG_ORDER_ID)
    }

    /**
     * 게이트웨이의 로거가 쓴 줄. 컨텍스트가 Boot의 로그 설정 없이 뜨므로 이 클래스만 돌리면 logback 기본값(DEBUG)이고, 다른 테스트 뒤에 돌면
     * Boot의 설정이다. 두 형식 모두 줄에 로거 이름의 마지막 마디(`LocalPgGateway`)를 남긴다. 기본값에서는 HttpClient의 wire 로그도 찍히므로
     * 게이트웨이의 줄만 골라 본다.
     */
    private fun CapturedOutput.gatewayLines(): List<String> {
        return all.lines().filter { "LocalPgGateway" in it }
    }
}
