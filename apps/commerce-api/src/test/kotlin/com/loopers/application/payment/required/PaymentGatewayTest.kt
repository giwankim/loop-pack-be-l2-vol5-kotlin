package com.loopers.application.payment.required

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.pg.LocalPgGatewayConfig
import com.loopers.domain.payment.Card
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
 * 테스트 PG를 쓰는 다른 테스트와 달리 기반 클래스를 상속하지 않는다. 호출마다의 로그 줄은 출력 캡처로 읽는다.
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
    }

    /**
     * 생성자 주입의 하나뿐인 예외다. `junit-platform.properties`의 `spring.test.constructor.autowire.mode=all` 때문에 생성자 파라미터는
     * Spring과 WireMock의 파라미터 resolver가 함께 맡으려 다투다 실패한다(탐침 보고서 §7의 1). 필드는 WireMock의 리스너가 넣는다.
     */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val pg by lazy { TestPg(server, callbackUrl = CALLBACK_URL) }

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
    fun `a 500 comes back as Unknown instead of being thrown`() {
        pg.answersCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, httpStatus = 500)

        val result = paymentGateway.requestPayment(PG_ORDER_ID, Money(AMOUNT), CARD)

        assertThat(result).isEqualTo(PaymentRequestResult.Unknown("HTTP 500"))
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

    /**
     * 게이트웨이의 로거가 쓴 줄. 컨텍스트가 Boot의 로그 설정 없이 뜨므로 이 클래스만 돌리면 logback 기본값(DEBUG)이고, 다른 테스트 뒤에 돌면
     * Boot의 설정이다. 두 형식 모두 줄에 로거 이름의 마지막 마디(`LocalPgGateway`)를 남긴다. 기본값에서는 HttpClient의 wire 로그도 찍히므로
     * 게이트웨이의 줄만 골라 본다.
     */
    private fun CapturedOutput.gatewayLines(): List<String> {
        return all.lines().filter { "LocalPgGateway" in it }
    }
}
