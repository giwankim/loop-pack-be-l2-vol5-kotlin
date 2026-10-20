package com.loopers.application.payment.required

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.pg.LocalPgGatewayConfig
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 연결 풀에서 연결을 얻지 못한 생성도 결과 미확인이다(ADR 0022 규칙 3). [PaymentGatewayTest]와 같은 가벼운 컨텍스트에서 풀의 연결만
 * 하나로 줄여, 멈춘 생성이 그 연결을 쥔 동안 두 번째 생성을 보낸다. 연결 수가 달라 컨텍스트를 하나 더 띄우므로 이 장면만 클래스로 떼어 낸다.
 *
 * 풀 대기는 운영의 200 ms이고 응답 한도도 운영의 1 s다. 멈춘 생성은 응답 한도까지 연결을 쥐므로, 두 번째 생성의 풀 대기가 그 안에 끝난다.
 * test 프로필의 응답 한도 200 ms에서는 두 한도가 같아 두 번째 생성이 연결을 얻을 수도 있다.
 */
@SpringJUnitConfig(classes = [LocalPgGatewayConfig::class], initializers = [ConfigDataApplicationContextInitializer::class])
@TestPropertySource(properties = ["pg.max-connections=1", "pg.response-timeout=1s"])
@EnableTestPg
class ExhaustedPoolPaymentGatewayTest(
    private val paymentGateway: PaymentGateway,
) {
    companion object {
        private const val STALLED_PG_ORDER_ID = "LP-ORD-0000000041"
        private const val PG_ORDER_ID = "LP-ORD-0000000042"
        private const val AMOUNT = 7_000L
        private val CARD = CreditCard(type = "TEST", number = "0000-0000-0000-0000")

        /** 멈춘 생성이 PG에 닿기를 기다리는 한도. 그 생성이 연결을 쥐는 응답 한도 1 s보다 짧다. */
        private val REACH_LIMIT: Duration = Duration.ofMillis(500)

        /** 멈춘 생성이 응답 한도 1 s에서 끝나기를 기다리는 한도. */
        private val STALLED_CREATE_LIMIT: Duration = Duration.ofSeconds(5)
    }

    /** [PaymentGatewayTest]의 같은 필드와 같은 까닭으로 생성자가 아니라 필드로 받는다. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val pg by lazy { TestPg(server) }

    @Test
    fun `a create that cannot get a pooled connection throws PgOutcomeUnknownException without reaching the PG`() {
        pg.stallsCreate(pgOrderId = STALLED_PG_ORDER_ID, amount = AMOUNT, card = CARD, transactionKey = "TEST-TX-0041")
        pg.acceptsCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, transactionKey = "TEST-TX-0042")
        val stalled = CompletableFuture.runAsync {
            assertThrows<PgOutcomeUnknownException> { paymentGateway.createTransaction(STALLED_PG_ORDER_ID, Money(AMOUNT), CARD) }
        }
        // WireMock은 지연 전에 요청을 기록하므로, PG에 닿았다면 멈춘 생성이 풀의 하나뿐인 연결을 쥐고 있다.
        await().atMost(REACH_LIMIT).until { pg.createCount() == 1 }

        assertThrows<PgOutcomeUnknownException> { paymentGateway.createTransaction(PG_ORDER_ID, Money(AMOUNT), CARD) }

        assertThat(pg.createCount()).isEqualTo(1)
        // 멈춘 생성이 끝나기를 기다려, 그 호출이 컨텍스트가 닫힐 때까지 남지 않게 한다.
        stalled.get(STALLED_CREATE_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
    }
}
