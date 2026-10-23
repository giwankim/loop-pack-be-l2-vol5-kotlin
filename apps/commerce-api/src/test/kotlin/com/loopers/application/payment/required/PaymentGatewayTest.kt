package com.loopers.application.payment.required

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.pg.LocalPgGateway
import com.loopers.adapter.integration.pg.LocalPgGatewayConfig
import com.loopers.application.payment.provided.MultiplePgTransactionsException
import com.loopers.application.payment.provided.PgOutcomeUnknownException
import com.loopers.application.payment.provided.PgRejectedException
import com.loopers.application.payment.provided.PgUnavailableException
import com.loopers.config.jackson.JacksonConfig
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.TestPg.Companion.REJECTED_BEFORE_CREATE
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.wiremock.spring.InjectWireMock

/** test 프로필과 다른 알림 주소. 생성 요청에 이 값이 실리면 어댑터가 알림 주소를 설정에서 읽은 것이다. */
private const val CALLBACK_URL = "http://127.0.0.1:9090/api/v1/payments/callback"

/**
 * [PaymentGateway]를 실제 HTTP로 테스트 PG에 보낸다(ADR 0022 규칙 6). 포트의 인터페이스만 부르고, 포트가 돌려준 값이나 던진 예외와
 * 테스트 PG가 받은 요청만 본다.
 *
 * 컨텍스트에는 어댑터와 그 `RestClient.Builder`를 만드는 Boot의 자동 설정만 있고 MySQL과 Redis는 없다. Boot의 매퍼와 컨버터 설정,
 * `supports/jackson`의 너그러운 설정([JacksonConfig])도 운영처럼 둔다. 그래서 JSON 해석의 테스트는 PG만의 매퍼가 builder에 먼저 들어간
 * 너그러운 매퍼를 밀어내는지를 운영과 같은 조건에서 본다. 설정 값은 `application.yml`을 test 프로필로 읽고 둘만 바꾼다.
 * - 응답 한도는 운영의 1 s다. PG가 받은 요청 수로 숨은 재전송이 꺼졌음을 보는 테스트는 운영의 시간 한도로 돈다(ADR 0022 규칙 6).
 *   HttpClient는 응답 한도가 재시도 간격 1 s보다 짧으면 503 재시도를 스스로 건너뛰므로, test 프로필의 200 ms에서는 재시도를 끄는 스위치를
 *   지워도 요청 수가 그대로다(탐침 보고서 §3).
 * - 알림 주소는 [CALLBACK_URL]이다.
 *
 * 이 저장소의 다른 Spring 테스트와 달리 기반 클래스를 상속하지 않는다. 테스트가 끝날 때마다 테스트 PG가 받은 요청이 모두 장면의 스텁과
 * 맞았는지 본다([everyRequestMatchedAScene]).
 * 연결 풀을 얻지 못한 생성은 풀의 연결 수가 다른 컨텍스트가 필요해 [ExhaustedPoolPaymentGatewayTest]에 있다.
 */
@SpringJUnitConfig(
    classes = [LocalPgGatewayConfig::class, LocalPgGateway::class, JacksonConfig::class],
    initializers = [ConfigDataApplicationContextInitializer::class],
)
@ImportAutoConfiguration(
    JacksonAutoConfiguration::class,
    HttpMessageConvertersAutoConfiguration::class,
    RestClientAutoConfiguration::class,
    ValidationAutoConfiguration::class,
)
@TestPropertySource(properties = ["pg.response-timeout=1s", "pg.callback-url=$CALLBACK_URL"])
@EnableTestPg
class PaymentGatewayTest(
    private val paymentGateway: PaymentGateway,
) {
    companion object {
        private const val PG_ORDER_ID = "0b6f3c1e-5a2d-4c8b-9e7f-1d2a3b4c5d6e"
        private const val OTHER_PG_ORDER_ID = "7c9d2e4f-1b3a-4d5c-8e6f-9a0b1c2d3e4f"
        private const val AMOUNT = 7_000L
        private const val TRANSACTION_KEY = "TEST-TX-0042"
        private val CARD = CreditCard(type = "TEST", number = "0000-0000-0000-0000")
        private val REQUEST = PgTransactionCreateRequest(
            pgOrderId = PG_ORDER_ID,
            amount = AMOUNT,
            cardType = CARD.type,
            cardNumber = CARD.number,
        )

        @JvmStatic
        fun invalidCreateInputs(): List<Arguments> {
            return listOf(
                arguments(REQUEST.copy(pgOrderId = ""), "pgOrderId"),
                arguments(REQUEST.copy(pgOrderId = "   "), "pgOrderId"),
                arguments(REQUEST.copy(amount = 0), "amount"),
                arguments(REQUEST.copy(amount = -1), "amount"),
                arguments(REQUEST.copy(cardType = ""), "cardType"),
                arguments(REQUEST.copy(cardType = "   "), "cardType"),
                arguments(REQUEST.copy(cardNumber = ""), "cardNumber"),
                arguments(REQUEST.copy(cardNumber = "   "), "cardNumber"),
                arguments(REQUEST.copy(cardNumber = "4111-1111-1111-1112"), "cardNumber"),
            )
        }

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
            """{"transactionKey":"$TRANSACTION_KEY","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}"""

        /**
         * 계약대로 읽을 수 없는 PG 거래. 생성의 202에도 두 조회의 200에도 같은 것들을 싣는다. 잘린 JSON, 문자열 금액, 빠진 거래 키나 금액,
         * 모르는 PG 상태, 빈 거래 키, 음수 금액, 소수 금액이다. 빠진 금액은 원시 타입이라 너그러운 매퍼라면 0원으로 읽어 잘 짜인 PG 거래가 된다.
         * 거래 키가 빈 PG 거래는 나중에 그 키로 조회할 수 없으므로 잘 짜인 PG 거래가 아니다. 음수 금액은 `Money`가 거절하는데, 그 예외가 그대로
         * 포트 밖으로 나가지 않고 해석 실패가 되어야 한다. 소수 금액은 원화 정수가 아니어서 정수로 잘라 읽지 않는다.
         */
        private val MALFORMED_TRANSACTIONS = listOf(
            """{"transactionKey":"$TRANSACTION_KEY","orderId":"$PG_ORDER_ID",""",
            """{"transactionKey":"$TRANSACTION_KEY","orderId":"$PG_ORDER_ID","amount":"$AMOUNT","status":"SUCCESS"}""",
            """{"orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}""",
            """{"transactionKey":"$TRANSACTION_KEY","orderId":"$PG_ORDER_ID","status":"SUCCESS"}""",
            """{"transactionKey":"$TRANSACTION_KEY","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"CANCELLED"}""",
            """{"transactionKey":"","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}""",
            """{"transactionKey":"   ","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"SUCCESS"}""",
            """{"transactionKey":"$TRANSACTION_KEY","orderId":"$PG_ORDER_ID","amount":-$AMOUNT,"status":"SUCCESS"}""",
            """{"transactionKey":"$TRANSACTION_KEY","orderId":"$PG_ORDER_ID","amount":$AMOUNT.5,"status":"SUCCESS"}""",
        )

        /** 202에 실렸지만 잘 짜인 PG 거래가 아닌 본문. */
        @JvmStatic
        fun unreadableTransactions(): List<String> {
            return MALFORMED_TRANSACTIONS
        }

        /** 거래 키 조회의 200에 실려 와도 PG 거래로 읽지 않는 본문. 읽을 수 없는 PG 거래, 빈 본문, 객체 대신 배열이다. */
        @JvmStatic
        fun unreadableTransactionBodies(): List<String> {
            return MALFORMED_TRANSACTIONS + listOf("", "[$WELL_FORMED_TRANSACTION]")
        }

        /**
         * PG 주문 번호 조회의 200에 실려 와도 PG 거래의 배열로 읽지 않는 본문. 읽을 수 없는 PG 거래를 담은 배열, 배열 대신 객체, null 원소,
         * null이다. 마지막은 둘째 원소의 PG 상태만 모르는 배열이다. 원소를 모두 읽은 뒤에 세므로 PG 거래가 둘이라고 답하지 않는다.
         */
        @JvmStatic
        fun unreadableArrayBodies(): List<String> {
            val unknownStatus =
                """{"transactionKey":"TEST-TX-0041","orderId":"$PG_ORDER_ID","amount":$AMOUNT,"status":"CANCELLED"}"""

            return MALFORMED_TRANSACTIONS.map { "[$it]" } +
                listOf(WELL_FORMED_TRANSACTION, "[null]", "null", "[$WELL_FORMED_TRANSACTION,$unknownStatus]")
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

    @ParameterizedTest
    @MethodSource("invalidCreateInputs")
    fun `invalid create input is rejected before sending a request`(
        request: PgTransactionCreateRequest,
        invalidField: String,
    ) {
        val exception = assertThrows<ConstraintViolationException> {
            paymentGateway.createTransaction(request)
        }

        assertThat(exception.constraintViolations).anySatisfy {
            assertThat(it.propertyPath.toString()).endsWith(".$invalidField")
        }
        assertThat(pg.requestCount()).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["4111111111111111", "4111-1111-1111-1111"])
    fun `a one won create accepts plain and hyphenated card numbers`(cardNumber: String) {
        val request = REQUEST.copy(amount = 1, cardNumber = cardNumber)
        pg.acceptsCreate(
            pgOrderId = PG_ORDER_ID,
            amount = 1,
            card = CARD.copy(number = cardNumber),
            transactionKey = TRANSACTION_KEY,
        )

        val transaction = paymentGateway.createTransaction(request)

        assertThat(transaction.amount).isEqualTo(Money(1))
        assertThat(pg.createCount()).isEqualTo(1)
    }

    @Test
    fun `an accepted create returns the PG's transaction and reaches the PG as one POST`() {
        pg.acceptsCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, transactionKey = TRANSACTION_KEY)

        val transaction = paymentGateway.createTransaction(REQUEST)

        assertThat(transaction).isEqualTo(
            PgTransaction(
                transactionKey = TRANSACTION_KEY,
                pgOrderId = PG_ORDER_ID,
                amount = Money(AMOUNT),
                status = PgStatus.PENDING,
            ),
        )
        assertThat(pg.createCount()).isEqualTo(1)
    }

    @ParameterizedTest
    @EnumSource(PgStatus::class)
    fun `a 202 returns the transaction whatever PG status it carries`(status: PgStatus) {
        pg.acceptsCreate(
            pgOrderId = PG_ORDER_ID,
            amount = AMOUNT,
            card = CARD,
            transactionKey = TRANSACTION_KEY,
            pgStatus = status.name,
        )

        val transaction = paymentGateway.createTransaction(REQUEST)

        assertThat(transaction).isEqualTo(
            PgTransaction(transactionKey = TRANSACTION_KEY, pgOrderId = PG_ORDER_ID, amount = Money(AMOUNT), status = status),
        )
    }

    @Test
    fun `an exact pre-create rejection throws PgRejectedException and reaches the PG as one POST`() {
        pg.rejectsBeforeCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD)

        val exception = assertThrows<PgRejectedException> { paymentGateway.createTransaction(REQUEST) }

        assertThat(exception.rejection).isEqualTo(PgRejection(pgOrderId = PG_ORDER_ID, amount = Money(AMOUNT)))
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
                {"code":"$REJECTED_BEFORE_CREATE","orderId":"$OTHER_PG_ORDER_ID","amount":7001,"transactionCreated":false}
            """,
        )

        val exception = assertThrows<PgRejectedException> { paymentGateway.createTransaction(REQUEST) }

        assertThat(exception.rejection).isEqualTo(PgRejection(pgOrderId = OTHER_PG_ORDER_ID, amount = Money(7_001)))
    }

    @ParameterizedTest
    @MethodSource("inexactRejections")
    fun `a 422 that is not the exact pre-create rejection throws PgOutcomeUnknownException`(body: String?) {
        pg.answersCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, httpStatus = 422, body = body)

        assertThrows<PgOutcomeUnknownException> { paymentGateway.createTransaction(REQUEST) }

        assertThat(pg.createCount()).isEqualTo(1)
    }

    /**
     * HttpClient의 기본값은 429와 503에 POST를 한 번 더 보낸다(ADR 0022 규칙 5). 503은 응답 한도가 재시도 간격 1 s 이상일 때만 다시 보내므로
     * 이 클래스가 운영의 응답 한도로 돌아야 이 수가 재시도를 끄는 스위치를 지킨다.
     */
    @ParameterizedTest
    @ValueSource(ints = [400, 404, 409, 429, 500, 502, 503, 504])
    fun `any other 4xx or 5xx throws PgOutcomeUnknownException with the status and reaches the PG as one POST`(httpStatus: Int) {
        pg.answersCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, httpStatus = httpStatus)

        val exception = assertThrows<PgOutcomeUnknownException> {
            paymentGateway.createTransaction(REQUEST)
        }

        assertThat(exception.message).isEqualTo("HTTP $httpStatus")
        assertThat(pg.createCount()).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 201])
    fun `a 2xx other than 202 throws PgOutcomeUnknownException even with a well-formed transaction`(httpStatus: Int) {
        pg.answersCreate(
            pgOrderId = PG_ORDER_ID,
            amount = AMOUNT,
            card = CARD,
            httpStatus = httpStatus,
            body = WELL_FORMED_TRANSACTION,
        )

        val exception = assertThrows<PgOutcomeUnknownException> {
            paymentGateway.createTransaction(REQUEST)
        }

        assertThat(exception.message).isEqualTo("HTTP $httpStatus")
    }

    /** 해석 오류의 메시지는 응답 본문의 조각을 담을 수 있어, 포트의 예외는 그 오류를 원인으로 잇지 않는다(결제 설계 3.5). */
    @ParameterizedTest
    @MethodSource("unreadableTransactions")
    fun `a 202 whose body is not a well-formed transaction throws PgOutcomeUnknownException without the parse error`(
        body: String,
    ) {
        pg.answersCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, httpStatus = 202, body = body)

        val exception = assertThrows<PgOutcomeUnknownException> {
            paymentGateway.createTransaction(REQUEST)
        }

        assertThat(exception.cause).isNull()
        assertThat(pg.createCount()).isEqualTo(1)
    }

    /**
     * PG 계약의 본문은 JSON뿐이다. JSON 문서는 YAML 문서이기도 해서, Boot의 builder가 클래스 경로에서 찾은 YAML 컨버터(Jackson 2)를
     * 두면 그것이 이 본문을 맡는다. 그 컨버터는 PG 거래를 만들지 못해 `RestClientException`이 아닌 `HttpMessageConversionException`을
     * 던졌고, 그 예외가 포트 밖으로 샜다. 게이트웨이의 `RestClient`에는 PG만의 JSON 컨버터 말고 PG의 본문을 읽는 컨버터가 없다(결제 설계 4.3).
     */
    @Test
    fun `a 202 with a well-formed transaction labeled as YAML throws PgOutcomeUnknownException`() {
        pg.answersCreate(
            pgOrderId = PG_ORDER_ID,
            amount = AMOUNT,
            card = CARD,
            httpStatus = 202,
            body = WELL_FORMED_TRANSACTION,
            contentType = "application/yaml",
        )

        assertThrows<PgOutcomeUnknownException> { paymentGateway.createTransaction(REQUEST) }
        assertThat(pg.createCount()).isEqualTo(1)
    }

    /** HttpClient의 기본값은 307에 본문까지 복사해 리다이렉트를 따라간다(ADR 0022 규칙 5). */
    @Test
    fun `a 307 throws PgOutcomeUnknownException and the PG receives one POST and nothing at the redirect target`() {
        pg.redirectsCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD)

        val exception = assertThrows<PgOutcomeUnknownException> {
            paymentGateway.createTransaction(REQUEST)
        }

        assertThat(exception.message).isEqualTo("HTTP 307")
        assertThat(pg.createCount()).isEqualTo(1)
        assertThat(pg.requestCount()).isEqualTo(1)
    }

    @Test
    fun `a connection dropped after the PG stored the transaction throws PgOutcomeUnknownException after one POST`() {
        pg.losesCreateResponse(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD)

        assertThrows<PgOutcomeUnknownException> { paymentGateway.createTransaction(REQUEST) }

        assertThat(pg.createCount()).isEqualTo(1)
    }

    /** 테스트 PG는 1.5 s 멈추고 어댑터는 응답 한도 1 s에서 그만 기다린다. 이 클래스의 느린 장면은 이것과 조회의 시간 초과 둘이다. */
    @Test
    fun `a create stalled past the response timeout throws PgOutcomeUnknownException after one POST`() {
        pg.stallsCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, transactionKey = TRANSACTION_KEY)

        assertThrows<PgOutcomeUnknownException> { paymentGateway.createTransaction(REQUEST) }

        assertThat(pg.createCount()).isEqualTo(1)
    }

    @Test
    fun `a key lookup answered 200 returns that one transaction and reaches the PG as one GET`() {
        val transaction = PgTransaction(
            transactionKey = TRANSACTION_KEY,
            pgOrderId = PG_ORDER_ID,
            amount = Money(AMOUNT),
            status = PgStatus.SUCCESS,
        )
        pg.hasTransaction(transaction)

        val found = paymentGateway.retrieveTransaction(transaction.transactionKey)

        assertThat(found).isEqualTo(transaction)
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @Test
    fun `a key lookup answered 404 returns null instead of throwing PgUnavailableException`() {
        pg.answersRetrieve(transactionKey = TRANSACTION_KEY, httpStatus = 404)

        val found = paymentGateway.retrieveTransaction(TRANSACTION_KEY)

        assertThat(found).isNull()
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @Test
    fun `a PG order ID lookup answered with an empty array returns null instead of throwing PgUnavailableException`() {
        pg.hasTransactions(pgOrderId = PG_ORDER_ID)

        val found = paymentGateway.retrieveTransactionByPgOrderId(PG_ORDER_ID)

        assertThat(found).isNull()
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @Test
    fun `a PG order ID lookup answered with one transaction returns that transaction`() {
        val transaction = PgTransaction(
            transactionKey = TRANSACTION_KEY,
            pgOrderId = PG_ORDER_ID,
            amount = Money(AMOUNT),
            status = PgStatus.PENDING,
        )
        pg.hasTransactions(pgOrderId = PG_ORDER_ID, transaction)

        val found = paymentGateway.retrieveTransactionByPgOrderId(PG_ORDER_ID)

        assertThat(found).isEqualTo(transaction)
    }

    /** PG의 차례나 PG 상태로 하나를 고르지 않는다. 어느 것이 결제의 PG 거래인지는 PG의 답이 말하지 않는다(ADR 0028). */
    @Test
    fun `a PG order ID lookup answered with two transactions throws MultiplePgTransactionsException after one GET`() {
        val failed = PgTransaction(
            transactionKey = "TEST-TX-0041",
            pgOrderId = PG_ORDER_ID,
            amount = Money(AMOUNT),
            status = PgStatus.FAILED,
        )
        val succeeded = PgTransaction(
            transactionKey = TRANSACTION_KEY,
            pgOrderId = PG_ORDER_ID,
            amount = Money(AMOUNT),
            status = PgStatus.SUCCESS,
        )
        pg.hasTransactions(pgOrderId = PG_ORDER_ID, failed, succeeded)

        val exception = assertThrows<MultiplePgTransactionsException> {
            paymentGateway.retrieveTransactionByPgOrderId(PG_ORDER_ID)
        }

        assertThat(exception.message).isEqualTo("PG 주문 번호 하나에 PG 거래가 2개다.")
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    /**
     * 거래 키는 URI builder의 경로 변수라 공백과 `?`까지 인코딩된다. 값을 경로에 그대로 이어 붙이면 공백은 URI가 되지 못하고, `?` 뒤는
     * query가 되어 스텁이 정확히 맞추는 경로와 다르다.
     */
    @Test
    fun `a key lookup sends the transaction key as one encoded path segment even with reserved characters`() {
        val transaction = PgTransaction(
            transactionKey = "TEST TX?0042",
            pgOrderId = PG_ORDER_ID,
            amount = Money(AMOUNT),
            status = PgStatus.SUCCESS,
        )
        pg.hasTransaction(transaction)

        val found = paymentGateway.retrieveTransaction(transaction.transactionKey)

        assertThat(found).isEqualTo(transaction)
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    /**
     * PG 주문 번호는 URI builder의 query 변수라 `+`와 `&`까지 인코딩된다. 값을 query에 그대로 이어 붙이면 `&`가 query를 둘로 가르고,
     * PG는 `+`를 공백으로 읽는다. 어느 쪽이든 스텁이 정확히 맞추는 `orderId`와 다르다.
     */
    @Test
    fun `a PG order ID lookup sends the number as one exact orderId query parameter even with reserved characters`() {
        val pgOrderId = "pg-order+42&orderId=43"
        pg.hasTransactions(pgOrderId = pgOrderId)

        val found = paymentGateway.retrieveTransactionByPgOrderId(pgOrderId)

        assertThat(found).isNull()
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    /** HttpClient의 기본 재시도는 끊긴 GET을 한 번 더 보낸다(탐침 보고서 §3). 조회의 재시도는 3단계가 명시적으로 정한다. */
    @Test
    fun `a lookup whose connection drops throws PgUnavailableException after exactly one GET`() {
        pg.losesLookupResponse()

        val exception = assertThrows<PgUnavailableException> { paymentGateway.retrieveTransaction(TRANSACTION_KEY) }

        assertThat(exception.message).isEqualTo("NoHttpResponseException")
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @Test
    fun `a lookup that outlasts the response timeout throws PgUnavailableException after exactly one GET`() {
        pg.stallsLookup()

        val exception = assertThrows<PgUnavailableException> { paymentGateway.retrieveTransactionByPgOrderId(PG_ORDER_ID) }

        assertThat(exception.message).isEqualTo("SocketTimeoutException")
        assertThat(pg.lookupCount()).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(ints = [201, 204, 400, 500, 503])
    fun `a key lookup answered with a status other than 200 or 404 throws PgUnavailableException with the status`(
        httpStatus: Int,
    ) {
        pg.answersRetrieve(transactionKey = TRANSACTION_KEY, httpStatus = httpStatus)

        val exception = assertThrows<PgUnavailableException> { paymentGateway.retrieveTransaction(TRANSACTION_KEY) }

        assertThat(exception.message).isEqualTo("HTTP $httpStatus")
    }

    @ParameterizedTest
    @ValueSource(ints = [201, 204, 400, 404, 500, 503])
    fun `a PG order ID lookup answered with a status other than 200, 404 included, throws PgUnavailableException`(
        httpStatus: Int,
    ) {
        pg.answersList(pgOrderId = PG_ORDER_ID, httpStatus = httpStatus)

        val exception = assertThrows<PgUnavailableException> { paymentGateway.retrieveTransactionByPgOrderId(PG_ORDER_ID) }

        assertThat(exception.message).isEqualTo("HTTP $httpStatus")
    }

    @ParameterizedTest
    @MethodSource("unreadableTransactionBodies")
    fun `a key lookup answered 200 with an unreadable body throws PgUnavailableException without the parse error`(body: String) {
        pg.answersRetrieve(transactionKey = TRANSACTION_KEY, httpStatus = 200, body = body)

        val exception = assertThrows<PgUnavailableException> { paymentGateway.retrieveTransaction(TRANSACTION_KEY) }

        assertThat(exception.cause).isNull()
    }

    @ParameterizedTest
    @MethodSource("unreadableArrayBodies")
    fun `a PG order ID lookup answered 200 with an unreadable body throws PgUnavailableException without the parse error`(
        body: String,
    ) {
        pg.answersList(pgOrderId = PG_ORDER_ID, httpStatus = 200, body = body)

        val exception = assertThrows<PgUnavailableException> { paymentGateway.retrieveTransactionByPgOrderId(PG_ORDER_ID) }

        assertThat(exception.cause).isNull()
    }
}
