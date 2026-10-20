package com.loopers.application.payment.required

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.pg.LocalPgGatewayConfig
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.wiremock.spring.InjectWireMock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * observation registry가 있으면 PG 호출도 다른 HTTP client 호출처럼 `http.client.requests`로 기록된다. 운영의 컨텍스트에는 Boot가 만든
 * registry가 있고, [PaymentGatewayTest]의 가벼운 컨텍스트에는 없다. registry를 더하면 컨텍스트가 하나 더 뜨므로 이 장면만 클래스로 떼어 낸다.
 *
 * registry에는 멈춘 observation을 모으는 handler 하나만 단다. 기록되는지와 그 낮은 카디널리티 값만 보고, 측정값을 내보내는 쪽은 보지 않는다.
 * 다른 게이트웨이 테스트처럼 운영의 응답 한도 1 s로 돈다.
 */
@SpringJUnitConfig(
    classes = [LocalPgGatewayConfig::class, ObservedPaymentGatewayTest.ObservationConfig::class],
    initializers = [ConfigDataApplicationContextInitializer::class],
)
@TestPropertySource(properties = ["pg.response-timeout=1s"])
@EnableTestPg
class ObservedPaymentGatewayTest(
    private val paymentGateway: PaymentGateway,
    private val observations: RecordedObservations,
) {
    companion object {
        private const val PG_ORDER_ID = "LP-ORD-0000000042"
        private const val AMOUNT = 7_000L
        private const val TRANSACTION_KEY = "TEST-TX-0042"
        private val CARD = CreditCard(type = "TEST", number = "0000-0000-0000-0000")
    }

    /** [PaymentGatewayTest]의 같은 필드와 같은 까닭으로 생성자가 아니라 필드로 받는다. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val pg by lazy { TestPg(server) }

    /** 테스트 PG가 받은 요청은 모두 장면의 스텁과 맞았어야 한다. 까닭은 [PaymentGatewayTest]의 같은 단언에 있다. */
    @AfterEach
    fun everyRequestMatchedAScene() {
        assertThat(pg.unmatchedRequests()).isEmpty()
    }

    /** 테스트 PG의 스텁처럼 모은 observation도 테스트 메서드마다 지운다. */
    @AfterEach
    fun clearObservations() {
        observations.clear()
    }

    @Test
    fun `a create is recorded as an HTTP client observation with its method, URI template and status`() {
        pg.acceptsCreate(pgOrderId = PG_ORDER_ID, amount = AMOUNT, card = CARD, transactionKey = TRANSACTION_KEY)

        paymentGateway.createTransaction(PG_ORDER_ID, Money(AMOUNT), CARD)

        val observation = observations.stopped().single()
        assertThat(observation.name).isEqualTo("http.client.requests")
        assertThat(observation.getLowCardinalityKeyValue("method")?.value).isEqualTo("POST")
        assertThat(observation.getLowCardinalityKeyValue("uri")?.value).isEqualTo("/api/v1/payments")
        assertThat(observation.getLowCardinalityKeyValue("status")?.value).isEqualTo("202")
    }

    /** 거래 키는 경로 변수라 측정값의 `uri`에는 템플릿만 남는다. 거래 키마다 측정값이 갈라지지 않는다. */
    @Test
    fun `a key lookup is recorded under its URI template without the transaction key`() {
        pg.hasTransaction(
            PgTransaction(
                transactionKey = TRANSACTION_KEY,
                pgOrderId = PG_ORDER_ID,
                amount = Money(AMOUNT),
                status = PgStatus.SUCCESS,
            ),
        )

        paymentGateway.retrieveTransaction(TRANSACTION_KEY)

        val observation = observations.stopped().single()
        assertThat(observation.name).isEqualTo("http.client.requests")
        assertThat(observation.getLowCardinalityKeyValue("uri")?.value).isEqualTo("/api/v1/payments/{transactionKey}")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ObservationConfig {
        @Bean
        fun recordedObservations(): RecordedObservations {
            return RecordedObservations()
        }

        @Bean
        fun observationRegistry(recordedObservations: RecordedObservations): ObservationRegistry {
            val registry = ObservationRegistry.create()
            registry.observationConfig().observationHandler(recordedObservations)

            return registry
        }
    }

    /** 멈춘 observation의 context를 멈춘 차례로 모은다. */
    class RecordedObservations : ObservationHandler<Observation.Context> {
        private val stopped = CopyOnWriteArrayList<Observation.Context>()

        override fun supportsContext(context: Observation.Context): Boolean {
            return true
        }

        override fun onStop(context: Observation.Context) {
            stopped += context
        }

        fun stopped(): List<Observation.Context> {
            return stopped.toList()
        }

        fun clear() {
            stopped.clear()
        }
    }
}
