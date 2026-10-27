package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.CommerceApiApplication
import com.loopers.adapter.integration.pg.LocalPgGateway
import com.loopers.application.order.OrderPaymentSagaSteps
import com.loopers.application.payment.PaymentModifyService
import com.loopers.application.payment.PgTransactionService
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.payment.required.PaymentGateway
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductListInfo
import com.loopers.application.product.provided.ProductListRequest
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentMethod
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.InvalidPaymentException
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.createPgTransactionOf
import com.loopers.domain.product.InsufficientStockException
import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.Pause
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.afterTheCreateIsAnswered
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.failTxBOnceAfterItsWrites
import com.loopers.support.pauseTheFirstTxBHoldingTheOrder
import com.loopers.support.productDeletedAt
import com.loopers.support.productStock
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.loopers.testcontainers.KafkaTestContainersConfig
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.retry.event.RetryOnRetryEvent
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.logging.LoggingApplicationListener
import org.springframework.boot.actuate.endpoint.SecurityContext
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant

/**
 * 고객의 확정이 PG로 결제하는 장면과, 대사가 끝나지 않은 결제의 결과를 되찾는 장면을 실제 MySQL과 테스트 PG로 확인한다(ADR 0024, 0028).
 * 과제의 명령 `--tests '*PaymentIntegrationTest'`가 이 클래스를 찾는다.
 *
 * 커밋하는 기반을 쓰므로 TX A와 TX B가 정말 커밋하고, 실제 어댑터가 HTTP로 테스트 PG에 닿는다. 장면마다 PG가 받은 생성 수와 조회 수를 세고,
 * 주문·결제·재고·잔액을 새 트랜잭션에서 다시 읽는다. 결제가 받을 PG 주문 번호는 확정 전에 `nextPgOrderId`로 정해 생성과 조회의 스텁에 넣는다.
 * 결제 중인 주문 곁의 쓰기와 읽기(관리자의 재고 수정, 상품 삭제, 예약된 재고, 고객 목록, 대사)는 PG 호출을 spy해 생성을 보내기 바로 앞에 넣고,
 * 생성을 보내기 전에 멈춘 프로세스도 그 spy가 흉내 낸다. 같은 키로 겨루는 두 확정은 결제를 만드는 Service를 spy해 늦은 쪽을 결제 삽입 앞에서,
 * 같은 답을 반영하는 두 TX B는 같은 Service를 spy해 앞선 쪽을 주문을 잠근 채 멈춘다. 외부 성공 뒤 내부 저장의 실패는 TX B의 빈을 spy해 넣는다.
 * 재시작은 같은 DB와 테스트 PG 위에 두 번째 실제 컨텍스트를 띄워 그 안의 새 Service로 본다([restarted]).
 *
 * A PG callback enters through [OrderPaymentReconciler], the port the web adapter calls once it has found the payment by its
 * PG order ID. The scenes where a callback meets a reconcile or confirm's own TX B reuse the spies above. A callback that beats
 * TX B runs from the PG-call spy right after the create is answered ([afterTheCreateIsAnswered]). A callback racing the
 * customer's reconcile is paused by the payment-modifying spy while it holds the order lock, like two racing reconciles.
 */
@EnableTestPg
class OrderPaymentIntegrationTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderPaymentReconciler: OrderPaymentReconciler,
    private val orderFinder: OrderFinder,
    private val paymentFinder: PaymentFinder,
    private val productFinder: ProductFinder,
    private val pointAccountFinder: PointAccountFinder,
    private val paymentGateway: PaymentGateway,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val retryRegistry: RetryRegistry,
    private val clock: TestClock,
    private val healthEndpoint: HealthEndpoint,
    private val healthEndpointGroups: HealthEndpointGroups,
    private val meterRegistry: MeterRegistry,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"
        private const val TRANSACTION_KEY = "20261022:TR:9577c5"
    }

    /**
     * Where a scene runs something beside a payment-pending order: writes and reads before the create goes out
     * ([whilePaymentPending]), or a callback after the create is answered ([afterTheCreateIsAnswered]). Unstubbed, it is the
     * real PG call.
     */
    @MockkSpyBean
    private lateinit var pgTransactionService: PgTransactionService

    /**
     * 같은 키로 겨루는 두 확정 가운데 하나를 결제를 만들기 앞에서 멈추는 자리([pauseBeforeStartingPaymentOf])이자, 같은 답을 반영하는 두 TX B
     * 가운데 앞선 것을 주문을 잠근 채 멈추는 자리([pauseTheFirstTxBHoldingTheOrder]).
     */
    @MockkSpyBean
    private lateinit var paymentModifyService: PaymentModifyService

    /** 외부 성공 뒤 내부 저장이 실패하는 자리([failTxBOnceAfterItsWrites]). stub이 없으면 실제 TX B 그대로다. */
    @MockkSpyBean
    private lateinit var orderPaymentSagaSteps: OrderPaymentSagaSteps

    private val pause = Pause()

    /**
     * 생성자 주입의 하나뿐인 예외다. `junit-platform.properties`의 `spring.test.constructor.autowire.mode=all` 때문에 생성자 파라미터는
     * Spring과 WireMock의 파라미터 resolver가 함께 맡으려 다투다 실패한다(탐침 보고서 §7의 1). 필드는 WireMock의 리스너가 넣는다.
     */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /**
     * 응용 계층이 남긴 로그. 맞지 않는 PG 거래는 결제의 반영이, PG 거래가 여럿인 조회는 조율하는 Service가 WARN으로 남기므로 둘을 품는
     * 패키지의 로거에 테스트마다 붙이고 뗀다. 테스트는 어느 클래스가 남겼는지 보지 않는다.
     */
    private val applicationLogs = ListAppender<ILoggingEvent>()

    private val applicationLogger = LoggerFactory.getLogger("com.loopers.application") as Logger

    /**
     * Logs from the PG adapter, where the gateway's configuration logs the breaker's state transitions. The base class resets
     * the breaker in its own `@BeforeEach`, which JUnit runs before this class's, so a WARN from that reset is never captured.
     */
    private val pgAdapterLogs = ListAppender<ILoggingEvent>()

    private val pgAdapterLogger = LoggerFactory.getLogger("com.loopers.adapter.integration.pg") as Logger

    @BeforeEach
    fun captureApplicationLogs() {
        applicationLogs.start()
        applicationLogger.addAppender(applicationLogs)
        pgAdapterLogs.start()
        pgAdapterLogger.addAppender(pgAdapterLogs)
    }

    @AfterEach
    fun releaseApplicationLogs() {
        applicationLogger.detachAppender(applicationLogs)
        pgAdapterLogger.detachAppender(pgAdapterLogs)
    }

    /** 과제 6절의 장면 1. 받아들여진 생성이 성공을 말하면 TX B가 주문을 확정하고 예약을 소비한다. 재고를 더 줄이지 않고 포인트도 쓰지 않는다. */
    @Test
    fun `confirming pays once through the PG, confirms the order on a successful transaction and keeps the reserved stock`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        charge(amount = 10_000)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "SUCCESS",
        )

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(confirmed.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(persisted.paymentMethod).isEqualTo(PaymentMethod.PG)
            assertThat(persisted.confirmedAt).isNotNull()
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
            assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(payment.idempotencyKey).isEqualTo(IDEMPOTENCY_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(10_000L.toBigInteger())
        }
    }

    /**
     * TX B confirms the order at the instant of the context's clock, the [TestClock] the breaker scenes move (ADR 0032 rule 8).
     * The scene pins the clock and moves it a day, so the stored confirmation time is an instant no other clock gives.
     */
    @Test
    fun `confirming stores the instant of the context's clock as the confirmation time`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        acceptNextCreate(testPg, amount = 2_000, card = request.toCreditCard())
        clock.pin(Instant.parse("2026-10-27T01:02:03.123456789Z"))
        clock.advance(Duration.ofDays(1))

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).confirmedAt).isEqualTo(Instant.parse("2026-10-28T01:02:03.123456Z"))
        }
    }

    /**
     * 과제 6절의 장면 4. 생성은 PG에 닿았지만 응답을 잃었다. PG 거래가 생겼을 수도 있으므로 결제는 결과 미확인, 주문은 결제 중인 주문으로
     * 남고 예약도 그대로다(ADR 0024 규칙 4). 그런 결제를 찾을 지표가 없으므로 주문 식별자를 담은 WARN을 한 줄 남긴다. 생성은 다시 보내지
     * 않는다. 대사가 PG 주문 번호로 그 PG 거래를 찾아 결제를 끝내고, 생성은 1 그대로다(ADR 0024 규칙 3).
     */
    @Test
    fun `a lost create response leaves the payment unknown until a reconcile finds the transaction, without another create`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.losesCreateResponse(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
        )

        val pending = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(pending.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(pending.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(testPg.createCount()).isOne()
        assertThat(applicationLogs.list.filter { it.level == Level.WARN }).singleElement().satisfies({
            assertThat(it.formattedMessage).contains("결과 미확인", "orderId=${order.id}")
        })
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(persisted.confirmedAt).isNull()
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(payment.transactionKey).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, amount = 2_000, status = PgStatus.SUCCESS))

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(paymentFinder.findByOrder(order.id).transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * PG의 답을 모르는 확정은 저장된 상태로 답한다. 그 상태는 TX A가 커밋한 그때가 아니라 답하는 지금의 것이다. PG 호출 동안 대사가 같은 PG
     * 거래를 찾아 결제를 끝냈으면, 응답을 잃은 확정도 결제 중인 주문이 아니라 확정된 주문으로 답한다. 생성은 1, 조회는 대사의 1이다.
     */
    @Test
    fun `an unknown create answers the state stored when it answers, confirmed if a reconcile finished the payment meanwhile`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.losesCreateResponse(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
        )
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, amount = 2_000, status = PgStatus.SUCCESS))
        var reconciled: OrderInfo? = null
        whilePaymentPending { reconciled = orderConfirmer.reconcile(user.id, order.id) }

        val answered = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(reconciled?.order?.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(answered.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(answered.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * 조회할 수 없는 것은 PG 거래가 없다는 뜻이 아니다(ADR 0022 규칙 3). 대사는 PG가 503으로 답하든 응답 한도를 넘겨 멈추든 결제와 예약을
     * 그대로 두고 저장된 상태로 답한다(ADR 0028 규칙 3). 생성은 다시 보내지 않는다. 조회는 한 번 다시 시도하므로 PG가 받은 조회는 2다
     * (ADR 0030 규칙 4). 브레이커가 열리기에는 표본이 모자라다.
     */
    @ParameterizedTest
    @ValueSource(strings = ["503", "stall"])
    fun `a reconcile whose lookup is unavailable answers the stored state and changes nothing`(unavailable: String) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val payment = confirmPending(testPg)
        if (unavailable == "503") {
            testPg.answersList(payment.pgOrderId, httpStatus = 503)
        } else {
            testPg.stallsLookup()
        }

        val stored = orderConfirmer.reconcile(user.id, order.id)

        assertThat(stored.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(stored.paymentStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            val persistedPayment = paymentFinder.findByOrder(order.id)
            assertThat(persistedPayment.status).isEqualTo(PaymentStatus.PENDING)
            assertThat(persistedPayment.transactionKey).isEqualTo(payment.transactionKey)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 10, 제한된 재조회. 조회 불가 한 번은 100 ms 뒤 한 번 다시 시도하고, 같은 대사가 그 시도가 찾은 PG 거래로 결제를 끝낸다
     * (ADR 0030 규칙 3, 4). PG가 받은 조회는 정확히 2, 생성은 1 그대로다. 게이트웨이는 Resilience4j의 aspect가 감싼 AOP 프록시다. AspectJ가
     * 없거나 애노테이션이 조용히 꺼지면 조회가 1이고 결제가 처리 중으로 남아 이 테스트가 실패한다(규칙 8).
     *
     * The wait is read from the retry's own event rather than timed, so load can't make it flaky. The subscription outlives the
     * test because Resilience4j's event publisher has no unsubscribe; later events only fill this test's discarded list.
     */
    @Test
    fun `one unavailable lookup is retried once and the retry's answer finishes the payment within the same reconcile`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.losesFirstLookupResponses(times = 1)
        testPg.hasTransactions(payment.pgOrderId, succeeded(payment))
        val retries = mutableListOf<RetryOnRetryEvent>()
        retryRegistry.retry(LocalPgGateway.PG_LOOKUP).eventPublisher.onRetry { retries += it }

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(retries).singleElement().satisfies({
            assertThat(it.numberOfRetryAttempts).isOne()
            assertThat(it.waitInterval).isEqualTo(Duration.ofMillis(100))
        })
        assertThat(AopUtils.isAopProxy(paymentGateway)).isTrue()
        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(paymentFinder.findByOrder(order.id).status).isEqualTo(PaymentStatus.SUCCESS)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 10, timeout과 회로 OPEN. 죽은 PG에 대사를 두 번 하면 두 조회의 시도 넷이 모두 실패해 브레이커 `pg-lookup`이 열린다
     * (최소 호출 4, 실패율 50 %). 열린 동안의 대사는 PG에 묻지 않는다. 세 대사 모두 저장된 상태로 답하고 결제와 예약은 그대로다(ADR 0030 규칙 6, 10).
     */
    @ParameterizedTest
    @ValueSource(strings = ["lost response", "503"])
    fun `two reconciles against a dead PG open the lookup breaker and a third answers the stored state without a lookup`(
        dead: String,
    ) {
        val product = prepareProduct(price = 1_000, stock = 5)
        prepareOrder(products = listOf(product), quantity = 2)
        val payment = confirmPending(testPg)
        if (dead == "503") {
            testPg.answersList(payment.pgOrderId, httpStatus = 503)
        } else {
            testPg.losesLookupResponse()
        }

        val againstDeadPg = List(2) { orderConfirmer.reconcile(user.id, order.id) }

        assertThat(testPg.lookupCount()).isEqualTo(4)
        assertThat(pgLookupBreaker().state).isEqualTo(CircuitBreaker.State.OPEN)

        val whileOpen = orderConfirmer.reconcile(user.id, order.id)

        (againstDeadPg + whileOpen).forEach { answer ->
            assertThat(answer.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(answer.paymentStatus).isEqualTo(PaymentStatus.PENDING)
        }
        assertThat(testPg.lookupCount()).isEqualTo(4)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertStillPending(order, product, stock = 3)
    }

    /**
     * 과제 6절의 장면 10, 재차단. 열린 브레이커는 60 s 동안 PG에 묻지 않는다. 테스트 시계를 59 s 옮긴 대사도 조회 0이다. 60 s를 넘긴 뒤의 대사가
     * HALF_OPEN의 탐색 둘을 쓰고, PG가 여전히 죽었으면 두 탐색이 모두 실패해 브레이커가 다시 열린다(ADR 0030 규칙 6, 8). 결제는 실패로 끝나지 않는다.
     */
    @Test
    fun `the open breaker sends no lookup within its 60 s wait and a probe against a still dead PG opens it again`() {
        val product = prepareProduct(price = 1_000, stock = 5)
        prepareOrder(products = listOf(product), quantity = 2)
        confirmPending(testPg)
        testPg.losesLookupResponse()
        openPgLookupBreaker()

        clock.advance(Duration.ofSeconds(59))
        val withinWait = orderConfirmer.reconcile(user.id, order.id)

        assertThat(testPg.lookupCount()).isEqualTo(4)
        assertThat(pgLookupBreaker().state).isEqualTo(CircuitBreaker.State.OPEN)

        clock.advance(Duration.ofSeconds(2))
        val probing = orderConfirmer.reconcile(user.id, order.id)

        listOf(withinWait, probing).forEach { answer ->
            assertThat(answer.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(answer.paymentStatus).isEqualTo(PaymentStatus.PENDING)
        }
        assertThat(testPg.lookupCount()).isEqualTo(6)
        assertThat(pgLookupBreaker().state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertStillPending(order, product, stock = 3)
    }

    /**
     * 과제 6절의 장면 10, 복구. 60 s를 넘긴 뒤 PG가 살아났으면 탐색한 대사가 결제를 끝내고 브레이커는 HALF_OPEN에 머문다. 결제 중인 다른 주문의
     * 대사가 두 번째로 성공한 조회가 되면 브레이커가 닫힌다(ADR 0030 규칙 6). PG가 살아난 것은 두 PG 주문 번호의 목록 조회 스텁이다. 끊는 스텁과
     * 우선순위가 같으면 WireMock은 나중에 더한 스텁으로 답한다.
     */
    @Test
    fun `after the wait a probe finishes its payment once the PG is back and a second successful lookup closes the breaker`() {
        val first = prepareOrder()
        val firstPayment = confirmPending(testPg, first)
        val second = prepareOrder()
        val secondPayment = confirmPending(testPg, second)
        testPg.losesLookupResponse()
        openPgLookupBreaker(first)
        testPg.hasTransactions(firstPayment.pgOrderId, succeeded(firstPayment))
        testPg.hasTransactions(secondPayment.pgOrderId, succeeded(secondPayment))
        clock.advance(Duration.ofSeconds(61))

        val probed = orderConfirmer.reconcile(first.userId, first.id)

        assertThat(probed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(probed.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(pgLookupBreaker().state).isEqualTo(CircuitBreaker.State.HALF_OPEN)

        val closing = orderConfirmer.reconcile(second.userId, second.id)

        assertThat(closing.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(pgLookupBreaker().state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(testPg.lookupCount()).isEqualTo(6)
        assertThat(testPg.createCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * 실패의 조건. PG 거래가 없다는 답(`null`)과 PG 거래가 여럿이라는 답은 PG가 답한 것이라 조회 불가가 아니다. 다시 시도하지 않으므로 대사마다
     * 조회는 1이고, 브레이커의 실패가 아니므로 최소 호출 4를 넘긴 다섯 번째 대사도 PG에 닿는다(ADR 0030 규칙 4). 결제는 처리 중 그대로다.
     */
    @ParameterizedTest
    @ValueSource(strings = ["no transaction", "several transactions"])
    fun `lookups that find no transaction or several are not retried and never open the breaker`(found: String) {
        val product = prepareProduct(price = 1_000, stock = 5)
        prepareOrder(products = listOf(product), quantity = 2)
        val payment = confirmPending(testPg)
        if (found == "no transaction") {
            testPg.hasTransactions(payment.pgOrderId)
        } else {
            testPg.hasTransactions(
                payment.pgOrderId,
                succeeded(payment),
                pgTransaction(payment.pgOrderId, amount = 2_000, status = PgStatus.SUCCESS, transactionKey = "20261022:TR:other"),
            )
        }

        (1..5).forEach { reconciled ->
            val stored = orderConfirmer.reconcile(user.id, order.id)

            assertThat(stored.paymentStatus).isEqualTo(PaymentStatus.PENDING)
            assertThat(testPg.lookupCount()).isEqualTo(reconciled)
        }

        assertThat(pgLookupBreaker().state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertStillPending(order, product, stock = 3)
    }

    /**
     * Each state transition of the breaker logs exactly one WARN (ADR 0030 rule 7). The first reconcile against the dead PG
     * leaves the breaker CLOSED because 2 samples are below the minimum of 4, and a reconcile while OPEN never reaches the PG, so
     * neither logs anything. Opening, probing, reopening, probing again and closing log one line each, in that order. There are
     * two pending orders because closing takes two successful lookups after the PG is back.
     */
    @Test
    fun `every state change of the lookup breaker logs one warning and a reconcile that changes no state logs none`() {
        val first = prepareOrder()
        val firstPayment = confirmPending(testPg, first)
        val second = prepareOrder()
        val secondPayment = confirmPending(testPg, second)
        testPg.losesLookupResponse()

        orderConfirmer.reconcile(first.userId, first.id)

        assertBreakerWarnings()

        orderConfirmer.reconcile(first.userId, first.id)

        assertBreakerWarnings(CircuitBreaker.State.CLOSED to CircuitBreaker.State.OPEN)

        orderConfirmer.reconcile(first.userId, first.id)
        clock.advance(Duration.ofSeconds(61))
        orderConfirmer.reconcile(first.userId, first.id)

        assertBreakerWarnings(
            CircuitBreaker.State.CLOSED to CircuitBreaker.State.OPEN,
            CircuitBreaker.State.OPEN to CircuitBreaker.State.HALF_OPEN,
            CircuitBreaker.State.HALF_OPEN to CircuitBreaker.State.OPEN,
        )

        testPg.hasTransactions(firstPayment.pgOrderId, succeeded(firstPayment))
        testPg.hasTransactions(secondPayment.pgOrderId, succeeded(secondPayment))
        clock.advance(Duration.ofSeconds(61))
        orderConfirmer.reconcile(first.userId, first.id)
        orderConfirmer.reconcile(second.userId, second.id)

        assertBreakerWarnings(
            CircuitBreaker.State.CLOSED to CircuitBreaker.State.OPEN,
            CircuitBreaker.State.OPEN to CircuitBreaker.State.HALF_OPEN,
            CircuitBreaker.State.HALF_OPEN to CircuitBreaker.State.OPEN,
            CircuitBreaker.State.OPEN to CircuitBreaker.State.HALF_OPEN,
            CircuitBreaker.State.HALF_OPEN to CircuitBreaker.State.CLOSED,
        )
        assertThat(pgLookupBreaker().state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(testPg.lookupCount()).isEqualTo(8)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The health indicator only reports the state (ADR 0030 rule 7). With the breaker open, the overall health is still UP while
     * the `circuitBreakers` component shows `pg-lookup` as OPEN: browsing, ordering and admin work without the PG. Because the
     * indicator may not fail, `pg-lookup` reports the custom status `CIRCUIT_OPEN` instead of DOWN, and Boot's aggregator leaves
     * statuses outside its order out of the overall status. The Boot
     * module's Micrometer binding exports the same state as a gauge.
     *
     * The state is read at `/actuator/health/pg-lookup`, a group of the breakers alone that always shows details; the
     * main endpoint shows only the overall status. The management server does not start in a mock web environment, so the
     * test reads the endpoint as a bean. The bean shows every component whatever the exposure, so the group's membership and
     * its details setting are asserted on their own.
     */
    @Test
    fun `an open lookup breaker shows in the health endpoint and the state gauge while the overall health stays UP`() {
        prepareOrder()
        confirmPending(testPg)
        testPg.losesLookupResponse()
        openPgLookupBreaker()

        assertThat(healthEndpoint.health().status).isEqualTo(Status.UP)
        val group = healthEndpointGroups.get(LocalPgGateway.PG_LOOKUP)!!
        assertThat(group.isMember("circuitBreakers")).isTrue()
        assertThat(group.isMember("db")).isFalse()
        assertThat(group.showDetails(SecurityContext.NONE)).isTrue()
        val pgLookupGroup = healthEndpoint.healthForPath(LocalPgGateway.PG_LOOKUP) as CompositeHealthDescriptor
        val circuitBreakers = pgLookupGroup.components!!["circuitBreakers"] as IndicatedHealthDescriptor
        assertThat(circuitBreakers.details[LocalPgGateway.PG_LOOKUP]).isInstanceOfSatisfying(Health::class.java) {
            assertThat(it.status.code).isEqualTo("CIRCUIT_OPEN")
            assertThat(it.details["state"]).isEqualTo(CircuitBreaker.State.OPEN)
        }
        assertThat(pgLookupStateGauge(CircuitBreaker.State.OPEN)).isEqualTo(1.0)
        assertThat(pgLookupStateGauge(CircuitBreaker.State.CLOSED)).isEqualTo(0.0)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * 과제 6절의 장면 2. 받아들여진 생성은 성공이 아니다. 처리 중인 PG 거래면 결제는 처리 중, 주문은 결제 중인 주문이고 예약은 그대로다.
     * 대사가 PG 주문 번호로 조회해 그 PG 거래의 성공을 찾은 뒤에야 확정의 TX B와 같은 경계로 주문이 확정된다(ADR 0024 규칙 5). 예약은
     * 더 줄이지 않고 소비하므로 재고는 3 그대로다.
     */
    @Test
    fun `a create accepted as pending leaves the order payment pending until a reconcile finds the transaction succeeded`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "PENDING",
        )

        val pending = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(pending.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(pending.paymentStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(persisted.paymentMethod).isEqualTo(PaymentMethod.PG)
            assertThat(persisted.confirmedAt).isNull()
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
            assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, amount = 2_000, status = PgStatus.SUCCESS))

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(persisted.confirmedAt).isNotNull()
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
            assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 6. 두 대사가 같은 답을 함께 반영해도 주문은 한 번만 바뀐다. 앞선 대사의 TX B를 주문을 잠근 채 결제에 반영하기 앞에서
     * 멈추면, 같은 PG 거래를 찾은 다른 대사의 TX B는 주문 잠금에서 기다린다. 풀린 뒤에는 끝난 결제를 다시 읽으므로 같은 답이 `Unchanged`라
     * 주문도 재고도 바꾸지 않는다(ADR 0024 규칙 5, ADR 0028 규칙 4). 성공이면 확정 시각이 하나이고, 실패면 예약을 한 번만 돌려줘 재고는 5다.
     * 두 번 돌려주면 7이다.
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, SUCCESS, 3", "FAILED, PAYMENT_FAILED, FAILED, 5")
    fun `two reconciles applying the same answer change the order and the stock once`(
        pgStatus: PgStatus,
        orderStatus: OrderStatus,
        paymentStatus: PaymentStatus,
        stock: Int,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, pgStatus))
        paymentModifyService.pauseTheFirstTxBHoldingTheOrder(pause)

        val first = inAnotherThread { orderConfirmer.reconcile(user.id, order.id) }
        pause.awaitHeld()
        val second = inAnotherThread { orderConfirmer.reconcile(user.id, order.id) }

        assertThat(second.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        val firstAnswer = first.await()
        val secondAnswer = second.await()
        listOf(firstAnswer, secondAnswer).forEach { answer ->
            assertThat(answer.order.status).isEqualTo(orderStatus)
            assertThat(answer.paymentStatus).isEqualTo(paymentStatus)
        }
        assertThat(secondAnswer.order.confirmedAt).isEqualTo(firstAnswer.order.confirmedAt)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(orderStatus)
            assertThat(paymentFinder.findByOrder(order.id).status).isEqualTo(paymentStatus)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /**
     * 과제 6절의 장면 6. 대사가 확정의 TX B와 같은 답을 함께 반영해도 주문은 한 번만 바뀐다. 확정의 TX B를 주문을 잠근 채 멈추면, 그 사이 같은
     * PG 거래를 찾은 대사의 TX B는 주문 잠금에서 기다리고, 풀린 뒤 끝난 결제에 같은 답이라 아무것도 바꾸지 않는다(ADR 0024 규칙 5).
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, SUCCESS, 3", "FAILED, PAYMENT_FAILED, FAILED, 5")
    fun `a reconcile racing the confirmation's TX B with the same answer changes the order and the stock once`(
        pgStatus: PgStatus,
        orderStatus: OrderStatus,
        paymentStatus: PaymentStatus,
        stock: Int,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = pgStatus.name,
        )
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, amount = 2_000, status = pgStatus))
        paymentModifyService.pauseTheFirstTxBHoldingTheOrder(pause)

        val confirming = inAnotherThread { orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request) }
        pause.awaitHeld()
        val reconciling = inAnotherThread { orderConfirmer.reconcile(user.id, order.id) }

        assertThat(reconciling.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        val confirmed = confirming.await()
        val reconciled = reconciling.await()
        listOf(confirmed, reconciled).forEach { answer ->
            assertThat(answer.order.status).isEqualTo(orderStatus)
            assertThat(answer.paymentStatus).isEqualTo(paymentStatus)
        }
        assertThat(reconciled.order.confirmedAt).isEqualTo(confirmed.order.confirmedAt)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(orderStatus)
            assertThat(paymentFinder.findByOrder(order.id).status).isEqualTo(paymentStatus)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /**
     * 과제 6절의 장면 7. PG는 성공했는데 그 답을 반영하는 TX B가 DB 오류로 되돌아갔다. 결제는 결과 미확인, 주문은 결제 중인 주문으로 남고
     * 예약도 그대로다. TX B는 다시 해도 되므로(ADR 0024 규칙 5) 대사가 같은 PG 거래를 조회해 같은 경계로 한 번 확정하고, 생성은 1 그대로다.
     */
    @Test
    fun `a TX B that fails after the PG succeeded leaves the payment unknown until a reconcile confirms it once`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "SUCCESS",
        )
        orderPaymentSagaSteps.failTxBOnceAfterItsWrites(entityManager)

        assertThrows<DataAccessResourceFailureException> { orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request) }

        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(payment.transactionKey).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, amount = 2_000, status = PgStatus.SUCCESS))

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(paymentFinder.findByOrder(order.id).transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 8. TX A가 커밋한 뒤 생성을 보내기 전에 프로세스가 멈췄다. 결제는 결과 미확인, 예약은 그대로이고 생성은 0이다. 커밋이
     * 발송 기록이라 보내지 못한 것과 응답을 잃은 것을 DB로 가를 수 없으므로, 어느 길도 생성을 다시 보내지 않는다(ADR 0024 규칙 2, 3).
     * 서비스는 상태를 갖지 않으므로 같은 DB와 테스트 PG에 새 요청을 보내는 것이 재시작이다. 같은 키의 재요청은 저장된 상태로 답하고,
     * PG 거래를 하나도 찾지 못한 대사도 아무것도 바꾸지 않는다. 0건은 PG 거래가 없다는 증거가 아니다(ADR 0022 규칙 3).
     */
    @Test
    fun `a restart before the create left keeps the payment unknown through a replay and an empty reconcile, without a create`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        stopBeforeSendingTheCreate()
        assertThrows<ProcessStopped> { orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request) }
        assertThat(testPg.createCount()).isZero()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(paymentFinder.findByOrder(order.id).status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
        testPg.hasTransactions(pgOrderId)

        val replayed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)
        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        listOf(replayed, reconciled).forEach { answer ->
            assertThat(answer.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(answer.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
        }
        assertThat(applicationLogs.list.filter { it.level == Level.WARN }).isEmpty()
        assertThat(testPg.createCount()).isZero()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(payment.transactionKey).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 8을 서비스와 클라이언트를 다시 만들어 본다. 첫 컨텍스트에 결제 중인 주문 둘을 남긴다. 하나는 TX A가 커밋한 뒤 생성을 보내기
     * 전에 멈췄고(생성 0), 하나는 생성의 응답을 잃었다(생성 1). 같은 DB와 테스트 PG 위에 [restarted]로 띄운 두 번째 컨텍스트는 뜨면서 PG에 생성도
     * 조회도 보내지 않는다. 시작할 때 도는 코드가 없고, TX A의 커밋이 발송 기록이라 생성은 다시 보내지 않는다(ADR 0024 규칙 2, 3).
     * 새 컨텍스트의 대사는 응답을 잃은 결제를 PG 거래의 거래 키로 확정하고, 보내지 못한 결제는 0건을 찾아 결과 미확인 그대로 둔다.
     * 생성은 결제마다 0과 1 그대로이고 재고는 한 번 소비된다.
     */
    @Test
    fun `a restarted context sends nothing on start and its reconcile finishes the payment whose create response was lost`() {
        val request = createOrderConfirmRequest()
        val stoppedProduct = prepareProduct(price = 1_000, stock = 5)
        val stopped = prepareOrder(products = listOf(stoppedProduct), quantity = 2)
        val stoppedPgOrderId = nextPgOrderId()
        stopBeforeSendingTheCreate()
        assertThrows<ProcessStopped> { orderConfirmer.confirm(stopped.userId, stopped.id, IDEMPOTENCY_KEY, request) }
        val lostProduct = prepareProduct(price = 1_000, stock = 5)
        val lost = prepareOrder(products = listOf(lostProduct), quantity = 2)
        val lostPgOrderId = nextPgOrderId()
        testPg.losesCreateResponse(pgOrderId = lostPgOrderId, amount = 2_000, card = request.toCreditCard())
        val pending = orderConfirmer.confirm(lost.userId, lost.id, "confirm-key-2", request)
        assertThat(pending.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
        testPg.hasTransactions(stoppedPgOrderId)
        testPg.hasTransactions(lostPgOrderId, pgTransaction(lostPgOrderId, amount = 2_000, status = PgStatus.SUCCESS))
        assertThat(testPg.createCount(stoppedPgOrderId)).isZero()
        assertThat(testPg.createCount(lostPgOrderId)).isOne()
        val requestsBeforeRestart = testPg.requestCount()

        restarted { context ->
            val restartedConfirmer = context.getBean(OrderConfirmer::class.java)

            assertThat(testPg.requestCount()).isEqualTo(requestsBeforeRestart)
            assertThat(testPg.createCount()).isOne()
            assertThat(testPg.lookupCount()).isZero()
            assertThat(AopTestUtils.getUltimateTargetObject<OrderConfirmer>(restartedConfirmer))
                .isNotSameAs(AopTestUtils.getUltimateTargetObject<OrderConfirmer>(orderConfirmer))
            assertThat(AopTestUtils.getUltimateTargetObject<PaymentGateway>(context.getBean(PaymentGateway::class.java)))
                .isNotSameAs(AopTestUtils.getUltimateTargetObject<PaymentGateway>(paymentGateway))
            inNewTransaction {
                assertThat(paymentFinder.findByOrder(stopped.id).status).isEqualTo(PaymentStatus.UNKNOWN)
                assertThat(productFinder.find(stoppedProduct.id).stock).isEqualTo(3)
                assertThat(paymentFinder.findByOrder(lost.id).status).isEqualTo(PaymentStatus.UNKNOWN)
                assertThat(productFinder.find(lostProduct.id).stock).isEqualTo(3)
            }

            val unsent = restartedConfirmer.reconcile(stopped.userId, stopped.id)
            val reconciled = restartedConfirmer.reconcile(lost.userId, lost.id)

            assertThat(unsent.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(unsent.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        }

        assertThat(testPg.createCount(stoppedPgOrderId)).isZero()
        assertThat(testPg.createCount(lostPgOrderId)).isOne()
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(stopped.userId, stopped.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            val unsentPayment = paymentFinder.findByOrder(stopped.id)
            assertThat(unsentPayment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(unsentPayment.transactionKey).isNull()
            assertThat(productFinder.find(stoppedProduct.id).stock).isEqualTo(3)
            assertThat(orderFinder.find(lost.userId, lost.id).status).isEqualTo(OrderStatus.CONFIRMED)
            val lostPayment = paymentFinder.findByOrder(lost.id)
            assertThat(lostPayment.status).isEqualTo(PaymentStatus.SUCCESS)
            assertThat(lostPayment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(lostProduct.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 9. PG 주문 번호 하나에 PG 거래가 여럿이거나, 찾은 PG 거래의 PG 주문 번호나 금액이 결제와 다르면 어느 것도 반영하지 않는다.
     * 충돌을 WARN으로 남기고 결제와 예약을 그대로 둔 채 저장된 상태로 답한다(ADR 0028 규칙 3). 여럿은 조회가 던지고, 맞지 않는 PG 거래는
     * 결제가 판단한다. 결제는 [stopBeforeSendingTheCreate]로 생성 없이 결과 미확인에 두므로 생성은 끝까지 0이다.
     */
    @ParameterizedTest
    @CsvSource(
        "several transactions, PG 거래가 2개다",
        "another PG order ID, reason=PG_ORDER_ID_MISMATCH",
        "another amount, reason=AMOUNT_MISMATCH",
    )
    fun `a reconcile that finds several or mismatched transactions applies none and logs a warning`(
        answer: String,
        warning: String,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val pgOrderId = nextPgOrderId()
        stopBeforeSendingTheCreate()
        assertThrows<ProcessStopped> { orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, createOrderConfirmRequest()) }
        when (answer) {
            "several transactions" -> testPg.hasTransactions(
                pgOrderId,
                pgTransaction(pgOrderId, amount = 2_000, status = PgStatus.SUCCESS),
                pgTransaction(pgOrderId, amount = 2_000, status = PgStatus.FAILED, transactionKey = "20261022:TR:other"),
            )

            "another PG order ID" -> testPg.hasTransactions(
                pgOrderId,
                pgTransaction(pgOrderId = "another-pg-order-id", amount = 2_000, status = PgStatus.SUCCESS),
            )

            "another amount" -> testPg.hasTransactions(
                pgOrderId,
                pgTransaction(pgOrderId, amount = 1_000, status = PgStatus.SUCCESS),
            )
        }

        val stored = orderConfirmer.reconcile(user.id, order.id)

        assertThat(stored.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(stored.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(applicationLogs.list.filter { it.level == Level.WARN }).singleElement().satisfies({
            assertThat(it.formattedMessage).contains(warning)
        })
        assertThat(testPg.createCount()).isZero()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(payment.transactionKey).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 3. 생성 전 거절은 PG 거래가 없다는 증거라서(ADR 0022) TX B가 결제를 실패로, 주문을 결제에 실패한 주문으로 바꾸고 TX A의 예약을
     * 한 번 돌려준다. 재고는 5 → 3 → 5다. 3은 PG 호출 동안 커밋된 재고이고, 돌려주지 않으면 끝이 3, 두 번 돌려주면 7이다. PG 거래가 없으므로
     * 거래 키도 없다.
     */
    @Test
    fun `a create rejected before creating fails the payment and the order and returns the reserved stock once`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.rejectsBeforeCreate(
            pgOrderId = nextPgOrderId(),
            amount = 2_000,
            card = request.toCreditCard(),
        )
        var reservedStock: Int? = null
        whilePaymentPending { reservedStock = inNewTransaction { productFinder.find(product.id).stock } }

        val failed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(reservedStock).isEqualTo(3)
        assertThat(failed.order.status).isEqualTo(OrderStatus.PAYMENT_FAILED)
        assertThat(failed.paymentStatus).isEqualTo(PaymentStatus.FAILED)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.PAYMENT_FAILED)
            assertThat(persisted.paymentMethod).isEqualTo(PaymentMethod.PG)
            assertThat(persisted.confirmedAt).isNull()
            assertThat(persisted.paidAmount).isNull()
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
            assertThat(payment.transactionKey).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(5)
        }
    }

    /**
     * 생성 전 거절도 결제와 맞아야 반영한다. PG가 되돌려 준 금액이 보낸 것과 다르면 충돌이라, TX B는 결제를 결과 미확인으로, 주문을 결제 중인 주문으로
     * 두고 예약도 돌려주지 않는다(ADR 0028 규칙 3). 다른 금액을 말하는 거절은 이 결제에 PG 거래가 없다는 증거가 아니다.
     */
    @Test
    fun `a rejection echoing another amount conflicts and leaves the payment unknown and the stock reserved`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        val otherAmountRejection =
            """{"code":"${TestPg.REJECTED_BEFORE_CREATE}","orderId":"$pgOrderId","amount":1000,"transactionCreated":false}"""
        testPg.answersCreate(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
            httpStatus = 422,
            body = otherAmountRejection,
        )

        val pending = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(pending.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(pending.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(payment.transactionKey).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 받아들여진 생성이 실패한 PG 거래를 말하면 생성 전 거절과 같이 끝난다. 다른 것은 PG 거래가 있으므로 결제가 그 거래 키를 남긴다는 것뿐이다.
     * 받아들여진 생성은 성공이 아니듯 실패도 PG 거래의 상태가 정한다(ADR 0024 규칙 1).
     */
    @Test
    fun `an accepted create whose transaction failed fails the order, keeps the transaction key and returns the stock once`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "FAILED",
        )

        val failed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(failed.order.status).isEqualTo(OrderStatus.PAYMENT_FAILED)
        assertThat(failed.paymentStatus).isEqualTo(PaymentStatus.FAILED)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.PAYMENT_FAILED)
            assertThat(persisted.confirmedAt).isNull()
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
            assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(5)
        }
    }

    /**
     * 과제 6절의 장면 11. 상품 삭제는 예약을 기다리지 않으므로 결제 중에 삭제된 상품은 정상이다. 결제가 실패하면 돌려주기는 그 상품을 건너뛰고
     * 다른 상품에만 돌려준다(ADR 0026 규칙 4). 삭제된 상품의 재고는 어느 포트로도 읽히지 않으므로 SQL로 읽어, 예약한 3에서 그대로인지 본다.
     */
    @Test
    fun `a failed payment skips a product deleted while it was pending, which stays deleted, and returns the other's units`() {
        val kept = prepareProduct(price = 1_000, stock = 5)
        val deleted = prepareProduct(price = 1_000, stock = 5)
        prepareOrder(products = listOf(kept, deleted), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.rejectsBeforeCreate(
            pgOrderId = nextPgOrderId(),
            amount = 4_000,
            card = request.toCreditCard(),
        )
        whilePaymentPending { deleteProduct(deleted) }

        val failed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(failed.order.status).isEqualTo(OrderStatus.PAYMENT_FAILED)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            assertThat(productFinder.find(kept.id).stock).isEqualTo(5)
            assertThat(productFinder.findOrNull(deleted.id)).isNull()
            assertThat(entityManager.productDeletedAt(deleted.id)).isNotNull()
            assertThat(entityManager.productStock(deleted.id)).isEqualTo(3)
        }
    }

    /**
     * 관리자의 재고 수정은 팔 수 있는 수량을 맞추고 예약에 손대지 않는다(ADR 0026 규칙 5). 재고 5에서 2를 예약해 3일 때 관리자가 10으로 맞추면 10이고,
     * 그 뒤 결제가 실패하면 예약한 2를 그 위에 더해 12다. 돌려주기가 예약 전의 5나 관리자의 10으로 되돌리지 않는다.
     */
    @Test
    fun `an admin stock update while the payment is pending sets the buyable stock and a failure adds the reservation on top`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "FAILED",
        )
        var reservedStock: Int? = null
        var adminSetStock: Int? = null
        whilePaymentPending {
            reservedStock = inNewTransaction { productFinder.find(product.id).stock }
            updateProductStock(quantity = 10)
            adminSetStock = inNewTransaction { productFinder.find(product.id).stock }
        }

        val failed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(reservedStock).isEqualTo(3)
        assertThat(adminSetStock).isEqualTo(10)
        assertThat(failed.order.status).isEqualTo(OrderStatus.PAYMENT_FAILED)
        inNewTransaction {
            assertThat(productFinder.find(product.id).stock).isEqualTo(12)
        }
    }

    /**
     * 예약이 마지막 재고를 가져가 품절이 된 상품은 결제가 실패해 예약이 돌아오면 고객 목록에서 다시 팔 수 있다. 돌려주기는 상품 목록을 바꾸는 아홉째
     * 쓰기라서 품절이 풀리면 revision을 올린다(ADR 0026 규칙 6). PG 호출 동안 읽은 목록은 품절을 보이고 TX A가 올린 revision의 키에 캐시된다.
     * TX B가 revision을 올리지 않으면 실패 뒤의 목록이 그 키를 다시 읽어 품절로 남는다.
     */
    @Test
    fun `a product sold out by a reservation is buyable in the customer list again after the payment fails`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 2)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.rejectsBeforeCreate(
            pgOrderId = nextPgOrderId(),
            amount = 2_000,
            card = request.toCreditCard(),
        )
        val listRequest = ProductListRequest()
        val revisionBefore = currentListRevision()
        var pendingList: ProductListInfo? = null
        var pendingRevision: Long? = null
        whilePaymentPending {
            pendingList = productFinder.findAll(listRequest)
            pendingRevision = currentListRevision()
        }

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(pendingList?.items?.map { it.soldOut }).containsExactly(true)
        assertThat(pendingRevision).isEqualTo(revisionBefore + 1)
        assertThat(productFinder.findAll(listRequest).items.map { it.soldOut }).containsExactly(false)
        assertThat(currentListRevision()).isEqualTo(revisionBefore + 2)
    }

    /**
     * 같은 키로 다시 확정하면 PG를 부르지도 조회하지도 않고 그때 저장된 상태로 답한다(ADR 0025 규칙 3). 첫 확정이 생성을 보내기 전에 다시 물으면
     * 결제 중인 주문과 결과 미확인 결제이고, 첫 확정이 끝난 뒤에 다시 물으면 확정된 주문이다. 첫 응답이 아니라 지금의 상태다.
     */
    @Test
    fun `confirming again with the same key answers the stored state, pending before the first create and confirmed after it`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        acceptNextCreate(testPg, amount = 2_000, card = request.toCreditCard())
        var replayedWhilePending: OrderInfo? = null
        whilePaymentPending { replayedWhilePending = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request) }

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)
        val replayedAfter = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(replayedWhilePending?.order?.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(replayedWhilePending?.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(replayedAfter.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(replayedAfter.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isZero()
        inNewTransaction {
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 5. 같은 주문을 같은 키로 함께 확정해도 주문 잠금을 먼저 얻은 하나만 재고를 예약하고 결제를 만들어 생성을 보낸다. 나머지는
     * 잠금을 기다린 뒤 결제가 있는 주문을 읽고 재요청으로 답한다(ADR 0025 규칙 3). 그때가 TX B 전이면 결제 중인 주문과 결과 미확인 결제, 뒤면
     * 확정된 주문이다. 주문이 하나라 결제도 하나다(`UK_PAYMENT_ON_ORDER_ID`). 예약이 한 번인지는 수량의 식으로 본다.
     */
    @Test
    fun `five concurrent confirmations of one draft with one key reserve once, send one create and replay the rest`() {
        val initialStock = 10
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = initialStock)), quantity = 2)
        val request = createOrderConfirmRequest()
        acceptNextCreate(testPg, amount = 2_000, card = request.toCreditCard())

        val results = runConcurrently(List(5) { { orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request) } })

        val outcomes = tally(results)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 5, rejections = 0, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(results.size)
        val answers = results.map { it.getOrThrow() }
        assertThat(answers.map { it.order.status to it.paymentStatus }).isSubsetOf(
            OrderStatus.PAYMENT_PENDING to PaymentStatus.UNKNOWN,
            OrderStatus.CONFIRMED to PaymentStatus.SUCCESS,
        )
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isZero()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(paymentFinder.findByOrder(order.id).idempotencyKey).isEqualTo(IDEMPOTENCY_KEY)
            val soldQuantity = persisted.lineItems.sumOf { item -> item.quantity }
            assertThat(productFinder.find(product.id).stock).isEqualTo(initialStock - soldQuantity)
        }
    }

    /** 과제 6절의 장면 5. 결제가 있는 주문에 다른 키로 확정하면 PG를 부르지 않고 거절되며 주문, 결제, 재고가 그대로다(ADR 0025 규칙 3). */
    @Test
    fun `confirming an order that has a payment with another key is rejected as a key mismatch and changes nothing`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val card = request.toCreditCard()
        acceptNextCreate(testPg, amount = 2_000, card = card, pgStatus = "PENDING")
        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        val exception = assertThrows<CoreException> { orderConfirmer.confirm(user.id, order.id, "another-key", request) }

        assertThat(exception.errorType).isEqualTo(ErrorType.IDEMPOTENCY_KEY_MISMATCH)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isZero()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
            assertThat(payment.idempotencyKey).isEqualTo(IDEMPOTENCY_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * 과제 6절의 장면 5. 키는 모든 사용자에 걸쳐 유일하므로 다른 사용자의 주문이 쓴 키로 확정하면 재고를 예약하기 전에 거절된다(ADR 0025 규칙 2, 3).
     * 거절된 주문은 확정 전 그대로이고 재고도 그대로다.
     */
    @Test
    fun `confirming a draft with a key another user's order used is rejected as in use and reserves nothing`() {
        val request = createOrderConfirmRequest()
        val paid = prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val draftProduct = prepareProduct(price = 1_000, stock = 5)
        val draft = prepareOrder(products = listOf(draftProduct), quantity = 2)
        acceptNextCreate(testPg, amount = 2_000, card = request.toCreditCard())
        orderConfirmer.confirm(paid.userId, paid.id, IDEMPOTENCY_KEY, request)

        val exception = assertThrows<CoreException> { orderConfirmer.confirm(draft.userId, draft.id, IDEMPOTENCY_KEY, request) }

        assertThat(exception.errorType).isEqualTo(ErrorType.IDEMPOTENCY_KEY_IN_USE)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(draft.userId, draft.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(persisted.paymentMethod).isNull()
            assertThat(persisted.updatedAt).isEqualTo(draft.updatedAt)
            assertThat(productFinder.find(draftProduct.id).stock).isEqualTo(5)
        }
    }

    /**
     * 키의 형식은 웹 경계를 거치지 않은 확정도 결제를 찾기 전에 본다(카탈로그 설계 5.25). `utf8mb4_bin`은 PAD SPACE라서 뒤 공백이 붙은 키로
     * 찾으면 공백 없는 키의 결제가 나온다. 형식을 먼저 보지 않으면 그 확정은 형식 오류 대신 `IDEMPOTENCY_KEY_IN_USE`로 거절된다.
     */
    @Test
    fun `a key with a trailing space is rejected as malformed before it can find the payment of the key without it`() {
        val request = createOrderConfirmRequest()
        val paid = prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val draftProduct = prepareProduct(price = 1_000, stock = 5)
        val draft = prepareOrder(products = listOf(draftProduct), quantity = 2)
        acceptNextCreate(testPg, amount = 2_000, card = request.toCreditCard())
        orderConfirmer.confirm(paid.userId, paid.id, IDEMPOTENCY_KEY, request)

        val exception = assertThrows<InvalidPaymentException> {
            orderConfirmer.confirm(draft.userId, draft.id, "$IDEMPOTENCY_KEY ", request)
        }

        assertThat(exception.message).isEqualTo("멱등 키는 공백 없는 보이는 ASCII 문자여야 합니다.")
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(draft.userId, draft.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(persisted.updatedAt).isEqualTo(draft.updatedAt)
            assertThat(productFinder.find(draftProduct.id).stock).isEqualTo(5)
        }
    }

    /** 키는 대소문자까지 그대로 견준다(ADR 0025 규칙 2). 대소문자만 다른 두 키는 다른 키라서 두 주문이 하나씩 써 저마다 확정된다. */
    @Test
    fun `keys that differ only in case are different keys, so two orders each confirm with one of them`() {
        val request = createOrderConfirmRequest()
        val card = request.toCreditCard()
        val upper = prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 1)
        val lower = prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 1)
        acceptNextCreate(testPg, amount = 1_000, card = card)
        acceptNextCreate(testPg, amount = 1_000, card = card)

        val upperConfirmed = orderConfirmer.confirm(upper.userId, upper.id, "Checkout-42", request)
        val lowerConfirmed = orderConfirmer.confirm(lower.userId, lower.id, "checkout-42", request)

        assertThat(upperConfirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(lowerConfirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(testPg.createCount()).isEqualTo(2)
        inNewTransaction {
            assertThat(paymentFinder.findByOrder(upper.id).idempotencyKey).isEqualTo("Checkout-42")
            assertThat(paymentFinder.findByOrder(lower.id).idempotencyKey).isEqualTo("checkout-42")
        }
    }

    /**
     * 과제 6절의 장면 5. 두 확정 전 주문이 같은 키로 겨루면 결제는 하나다. 늦은 쪽은 키가 쓰이기 전에 사전 조건을 지났으므로 결제 삽입이
     * `UK_PAYMENT_ON_IDEMPOTENCY_KEY`에 걸린다. 그 TX A는 예약과 함께 되돌아가고, 조율하는 Service가 트랜잭션 밖에서 `IDEMPOTENCY_KEY_IN_USE`로
     * 옮긴다(ADR 0025 규칙 3). 늦은 쪽을 사전 조건과 삽입 사이에서 멈추고 그동안 다른 주문의 확정을 끝내 경합을 정해 둔다. 두 주문은 다른 상품을
     * 사므로 상품 잠금에서 서로 기다리지 않는다. 같은 키의 결제는 그 유일 제약이 하나로 묶으므로, 결제가 하나인지는 앞선 쪽의 결제가 그 키를 가졌고
     * 늦은 쪽의 주문에 결제수단이 없는 것으로 본다.
     */
    @Test
    fun `two drafts racing with one key make one payment and the loser is rejected as in use with its stock untouched`() {
        val request = createOrderConfirmRequest()
        val loserProduct = prepareProduct(price = 1_000, stock = 5)
        val loser = prepareOrder(products = listOf(loserProduct), quantity = 2)
        val winnerProduct = prepareProduct(price = 1_000, stock = 5)
        val winner = prepareOrder(products = listOf(winnerProduct), quantity = 2)
        acceptNextCreate(testPg, amount = 2_000, card = request.toCreditCard())
        nextPgOrderId()
        pauseBeforeStartingPaymentOf(loser)

        val losing = inAnotherThread { orderConfirmer.confirm(loser.userId, loser.id, IDEMPOTENCY_KEY, request) }
        pause.awaitHeld()
        val won = orderConfirmer.confirm(winner.userId, winner.id, IDEMPOTENCY_KEY, request)
        pause.release()

        val exception = assertThrows<CoreException> { losing.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.IDEMPOTENCY_KEY_IN_USE)
        assertThat(won.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(loser.userId, loser.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(persisted.paymentMethod).isNull()
            assertThat(persisted.updatedAt).isEqualTo(loser.updatedAt)
            assertThat(productFinder.find(loserProduct.id).stock).isEqualTo(5)
            assertThat(productFinder.find(winnerProduct.id).stock).isEqualTo(3)
            assertThat(paymentFinder.findByOrder(winner.id).idempotencyKey).isEqualTo(IDEMPOTENCY_KEY)
        }
    }

    /** 과제 6절의 장면 11. 재고가 모자라면 TX A 안에서 결제를 만들기 전에 거절된다. 주문은 확정 전 그대로이고 PG는 불리지 않는다. */
    @Test
    fun `a shortage rejects the confirmation before the PG with the draft and the stock unchanged`() {
        prepareOrder(products = listOf(prepareProduct(stock = 1)), quantity = 2)

        assertThrows<InsufficientStockException> {
            orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, createOrderConfirmRequest())
        }

        assertThat(testPg.createCount()).isZero()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(persisted.paymentMethod).isNull()
            assertThat(persisted.updatedAt).isEqualTo(order.updatedAt)
            assertThat(productFinder.find(product.id).stock).isEqualTo(1)
        }
    }

    /** 과제 6절의 장면 11. 삭제된 상품이 있으면 주문할 수 없는 상품으로 거절된다. 남은 상품의 재고도 그대로다. */
    @Test
    fun `a deleted product rejects the confirmation before the PG with the draft and the other stock unchanged`() {
        val kept = prepareProduct(stock = 5)
        val deleted = prepareProduct(stock = 5)
        prepareOrder(products = listOf(kept, deleted), quantity = 1)
        deleteProduct(deleted)

        val exception = assertThrows<CoreException> {
            orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, createOrderConfirmRequest())
        }

        assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
        assertThat(testPg.createCount()).isZero()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(persisted.updatedAt).isEqualTo(order.updatedAt)
            assertThat(productFinder.find(kept.id).stock).isEqualTo(5)
        }
    }

    /**
     * PG의 대사 포트는 고객의 대사와 같은 PG 주문 번호 조회와 같은 TX B로 결제를 끝낸다(ADR 0031 규칙 3). 요청자 없이 주문의 결제를 대사하고,
     * 성공이면 주문을 확정하고 예약을 소비하며, 실패면 예약을 한 번 돌려줘 재고는 5다. 생성은 다시 보내지 않는다.
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, SUCCESS, 3", "FAILED, PAYMENT_FAILED, FAILED, 5")
    fun `the PG's reconcile finishes a pending payment with the transaction its lookup finds`(
        pgStatus: PgStatus,
        orderStatus: OrderStatus,
        paymentStatus: PaymentStatus,
        stock: Int,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, pgStatus))

        orderPaymentReconciler.reconcile(order.id)

        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(orderStatus)
            val persistedPayment = paymentFinder.findByOrder(order.id)
            assertThat(persistedPayment.status).isEqualTo(paymentStatus)
            assertThat(persistedPayment.transactionKey).isEqualTo(payment.transactionKey)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /**
     * 조회할 수 없으면 PG의 대사 포트는 `PG_UNAVAILABLE`을 던진다. 묻지 못했으니 PG가 알림을 다시 보내야 한다(ADR 0031 규칙 6). 같은 경우
     * 고객의 대사는 지금처럼 저장된 상태로 답한다. 어느 쪽도 결제와 예약을 바꾸지 않는다.
     * Each side retries its lookup once, so the PG receives 2 lookups per reconcile and 4 in all (ADR 0030 rule 4).
     */
    @ParameterizedTest
    @ValueSource(strings = ["503", "stall"])
    fun `the PG's reconcile throws PG_UNAVAILABLE on an unavailable lookup while the customer's answers the stored state`(
        unavailable: String,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val payment = confirmPending(testPg)
        if (unavailable == "503") {
            testPg.answersList(payment.pgOrderId, httpStatus = 503)
        } else {
            testPg.stallsLookup()
        }

        val exception = assertThrows<CoreException> { orderPaymentReconciler.reconcile(order.id) }
        val stored = orderConfirmer.reconcile(user.id, order.id)

        assertThat(exception.errorType).isEqualTo(ErrorType.PG_UNAVAILABLE)
        assertThat(stored.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(stored.paymentStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isEqualTo(4)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(paymentFinder.findByOrder(order.id).status).isEqualTo(PaymentStatus.PENDING)
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /**
     * An open breaker is unavailable too. While it is open, the PG's reconcile throws `PG_UNAVAILABLE` without a lookup, so a
     * resending PG tries again after the wait, and the customer's reconcile of the same order answers the stored state
     * (ADR 0030 rule 10, ADR 0031 rule 6). The breaker opens on the customer's two reconciles against a dead PG, 4 lookups,
     * and the count stays at 4. The payment and the reservation are unchanged.
     */
    @Test
    fun `the PG's reconcile throws PG_UNAVAILABLE on an open breaker without a lookup, the customer's answers stored state`() {
        val product = prepareProduct(price = 1_000, stock = 5)
        prepareOrder(products = listOf(product), quantity = 2)
        confirmPending(testPg)
        testPg.losesLookupResponse()
        openPgLookupBreaker()

        val exception = assertThrows<CoreException> { orderPaymentReconciler.reconcile(order.id) }
        val stored = orderConfirmer.reconcile(user.id, order.id)

        assertThat(exception.errorType).isEqualTo(ErrorType.PG_UNAVAILABLE)
        assertThat(stored.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(stored.paymentStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(testPg.lookupCount()).isEqualTo(4)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        assertStillPending(order, product, stock = 3)
    }

    /**
     * 결제가 끝난 주문은 PG의 대사 포트도 PG에 조회하지 않고 돌아간다. 다시 받아도 바뀌는 것이 없고, 실패한 결제의 반환도 다시 일어나지 않는다.
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, 3", "FAILED, PAYMENT_FAILED, 5")
    fun `the PG's reconcile of a finished payment sends no lookup and changes nothing`(
        pgStatus: String,
        orderStatus: OrderStatus,
        stock: Int,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        confirmOrder(testPg, pgStatus = pgStatus)

        repeat(2) { orderPaymentReconciler.reconcile(order.id) }

        assertThat(testPg.lookupCount()).isZero()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(orderStatus)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /** 확정 전 주문에는 대사할 결제가 없다. PG의 대사 포트도 고객의 대사처럼 PG에 아무것도 보내지 않고 거절한다. */
    @Test
    fun `the PG's reconcile of a draft is rejected as payment not started without a lookup`() {
        prepareOrder(products = listOf(prepareProduct(stock = 5)), quantity = 2)

        val exception = assertThrows<CoreException> { orderPaymentReconciler.reconcile(order.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PAYMENT_NOT_STARTED)
        assertThat(testPg.requestCount()).isZero()
    }

    /**
     * Scene 6 of the assignment, callback first. The callback arrives after the PG accepted confirm's create but before confirm's
     * TX B applies it. The callback's lookup and TX B finish the payment, so the create's answer reaches a finished payment and is
     * `Unchanged` (ADR 0031 rule 8). A create that said PENDING doesn't move the payment back either. Confirm answers with the
     * finished order its TX B re-read. On SUCCESS there is one `confirmedAt` and the stock stays at 3 after 5 → 3; on FAILED it
     * goes 5 → 3 → 5 once.
     */
    @ParameterizedTest
    @CsvSource(
        "SUCCESS, SUCCESS, CONFIRMED, SUCCESS, 3",
        "PENDING, SUCCESS, CONFIRMED, SUCCESS, 3",
        "FAILED, FAILED, PAYMENT_FAILED, FAILED, 5",
        "PENDING, FAILED, PAYMENT_FAILED, FAILED, 5",
    )
    fun `a callback applied before the confirmation's TX B finishes the payment once and the late create answer changes nothing`(
        createStatus: PgStatus,
        lookupStatus: PgStatus,
        orderStatus: OrderStatus,
        paymentStatus: PaymentStatus,
        stock: Int,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = createStatus.name,
        )
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, amount = 2_000, status = lookupStatus))
        var reservedStock: Int? = null
        var orderAfterCallback: Order? = null
        var stockAfterCallback: Int? = null
        pgTransactionService.afterTheCreateIsAnswered {
            reservedStock = inNewTransaction { productFinder.find(product.id).stock }
            orderPaymentReconciler.reconcile(order.id)
            orderAfterCallback = inNewTransaction { orderFinder.find(user.id, order.id) }
            stockAfterCallback = inNewTransaction { productFinder.find(product.id).stock }
        }

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(reservedStock).isEqualTo(3)
        assertThat(orderAfterCallback?.status).isEqualTo(orderStatus)
        assertThat(stockAfterCallback).isEqualTo(stock)
        assertThat(confirmed.order.status).isEqualTo(orderStatus)
        assertThat(confirmed.paymentStatus).isEqualTo(paymentStatus)
        assertThat(confirmed.order.confirmedAt).isEqualTo(orderAfterCallback?.confirmedAt)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(orderStatus)
            assertThat(persisted.confirmedAt).isEqualTo(orderAfterCallback?.confirmedAt)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(paymentStatus)
            assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /**
     * Scene 6 of the assignment, lookup first. A callback that arrives after the customer's reconcile finished the payment returns
     * without asking the PG and changes nothing (ADR 0031 rules 6 and 8). `confirmedAt` and the order's `updatedAt` stay as the
     * reconcile left them, and a failed payment's reservation is not returned a second time.
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, SUCCESS, 3", "FAILED, PAYMENT_FAILED, FAILED, 5")
    fun `a callback after the customer's reconcile finished the payment sends no lookup and changes nothing`(
        pgStatus: PgStatus,
        orderStatus: OrderStatus,
        paymentStatus: PaymentStatus,
        stock: Int,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, pgStatus))
        val reconciled = orderConfirmer.reconcile(user.id, order.id)
        val reconciledOrder = inNewTransaction { orderFinder.find(user.id, order.id) }

        orderPaymentReconciler.reconcile(order.id)

        assertThat(reconciled.order.status).isEqualTo(orderStatus)
        assertThat(reconciled.paymentStatus).isEqualTo(paymentStatus)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(orderStatus)
            assertThat(persisted.confirmedAt).isEqualTo(reconciledOrder.confirmedAt)
            assertThat(persisted.updatedAt).isEqualTo(reconciledOrder.updatedAt)
            assertThat(paymentFinder.findByOrder(order.id).status).isEqualTo(paymentStatus)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /**
     * Scene 6 of the assignment, a callback and the customer's reconcile together. Both see a payment-pending order and find the
     * same PG transaction. The first TX B is paused while it holds the order lock, just before it applies the transaction, so the
     * other TX B waits on that lock. Once released, it re-reads a finished payment and gets `Unchanged` (ADR 0031 rule 8, ADR 0024
     * rule 5). Whichever side takes the lock first, the order changes once: one `confirmedAt` on SUCCESS, and on FAILED the stock
     * goes 5 → 3 → 5. Returning the reservation twice would leave 7.
     */
    @ParameterizedTest
    @CsvSource(
        "callback, SUCCESS, CONFIRMED, SUCCESS, 3",
        "callback, FAILED, PAYMENT_FAILED, FAILED, 5",
        "reconcile, SUCCESS, CONFIRMED, SUCCESS, 3",
        "reconcile, FAILED, PAYMENT_FAILED, FAILED, 5",
    )
    fun `a callback and the customer's reconcile applying the same answer together change the order and the stock once`(
        lockHolder: String,
        pgStatus: PgStatus,
        orderStatus: OrderStatus,
        paymentStatus: PaymentStatus,
        stock: Int,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val payment = confirmPending(testPg)
        val reservedStock = inNewTransaction { productFinder.find(product.id).stock }
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, pgStatus))
        paymentModifyService.pauseTheFirstTxBHoldingTheOrder(pause)
        var reconciled: OrderInfo? = null
        val callingBack = { orderPaymentReconciler.reconcile(order.id) }
        val reconciling = { reconciled = orderConfirmer.reconcile(user.id, order.id) }
        val (holdingCall, waitingCall) = if (lockHolder == "callback") callingBack to reconciling else reconciling to callingBack

        val holding = inAnotherThread(holdingCall)
        pause.awaitHeld()
        val waiting = inAnotherThread(waitingCall)

        assertThat(waiting.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        holding.await()
        waiting.await()
        assertThat(reservedStock).isEqualTo(3)
        assertThat(reconciled?.order?.status).isEqualTo(orderStatus)
        assertThat(reconciled?.paymentStatus).isEqualTo(paymentStatus)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(orderStatus)
            assertThat(persisted.confirmedAt).isEqualTo(reconciled?.order?.confirmedAt)
            assertThat(paymentFinder.findByOrder(order.id).status).isEqualTo(paymentStatus)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /**
     * The create's response was lost, so the commerce never learned the transaction key. The callback looks the payment up by its
     * PG order ID, not by a key, so it still finds the transaction, finishes the payment and records the PG's transaction key
     * (ADR 0031 rule 4). Create stays at 1. On FAILED the reservation comes back once and the stock is 5.
     */
    @ParameterizedTest
    @CsvSource("SUCCESS, CONFIRMED, SUCCESS, 3", "FAILED, PAYMENT_FAILED, FAILED, 5")
    fun `a callback records the PG's transaction key on a payment whose create response was lost, without another create`(
        pgStatus: PgStatus,
        orderStatus: OrderStatus,
        paymentStatus: PaymentStatus,
        stock: Int,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.losesCreateResponse(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
        )
        val pending = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)
        assertThat(pending.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
        inNewTransaction {
            assertThat(paymentFinder.findByOrder(order.id).transactionKey).isNull()
        }
        testPg.hasTransactions(pgOrderId, pgTransaction(pgOrderId, amount = 2_000, status = pgStatus))

        orderPaymentReconciler.reconcile(order.id)

        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(orderStatus)
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(paymentStatus)
            assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /**
     * 조율하는 Service는 `NEVER`라서 트랜잭션 안에서 부르면 Spring이 들어가기 전에 거절한다(ADR 0028 규칙 2). 트랜잭션 안에서 PG를 부르면
     * 결과 미확인의 예외가 결제의 저장까지 되돌리므로, 그 실수를 실행이 막는다.
     */
    @Test
    fun `confirming inside a transaction is refused before anything is reserved or sent`() {
        prepareOrder(products = listOf(prepareProduct(stock = 5)), quantity = 2)

        assertThrows<IllegalTransactionStateException> {
            inNewTransaction { orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, createOrderConfirmRequest()) }
        }

        assertThat(testPg.createCount()).isZero()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.DRAFT)
            assertThat(productFinder.find(product.id).stock).isEqualTo(5)
        }
    }

    /** 대사도 같은 조율하는 Service라 트랜잭션 안에서 부르면 PG에 조회하기 전에 거절된다(ADR 0028 규칙 2). */
    @Test
    fun `reconciling inside a transaction is refused before anything is looked up`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        confirmOrder(testPg, pgStatus = "PENDING")

        assertThrows<IllegalTransactionStateException> {
            inNewTransaction { orderConfirmer.reconcile(user.id, order.id) }
        }

        assertThat(testPg.lookupCount()).isZero()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        }
    }

    /**
     * 확정의 PG 호출이 생성을 보내기 바로 앞에서 [action]을 한다. TX A가 커밋한 뒤이고 TX B 전이라, [action]의 쓰기는 결제 중인 주문 곁에서 커밋되고
     * 읽기는 TX A가 커밋한 것을 본다. PG 호출은 어느 트랜잭션에도 들지 않으므로 [action]이 부른 포트는 자기 트랜잭션에서 돈다. Service는 CGLIB
     * 프록시이므로 프록시가 감싼 spy에 stub한다.
     */
    private fun whilePaymentPending(action: () -> Unit) {
        val target = AopTestUtils.getUltimateTargetObject<PgTransactionService>(pgTransactionService)
        every { target.create(any(), any()) } answers {
            action()
            callOriginal()
        }
    }

    /**
     * 확정의 PG 호출이 생성을 보내기 전에 [ProcessStopped]를 한 번 던진다. TX A가 커밋한 뒤 POST 전에 프로세스가 멈춘 것과 같다. 그 뒤의
     * 호출은 원래 호출 그대로라, 재요청이나 대사가 생성을 보내면 PG가 받은 생성 수에 드러난다.
     */
    private fun stopBeforeSendingTheCreate() {
        val target = AopTestUtils.getUltimateTargetObject<PgTransactionService>(pgTransactionService)
        every { target.create(any(), any()) } throws ProcessStopped() andThenAnswer { callOriginal() }
    }

    /** 결제 [payment]의 PG 주문 번호, 금액, 거래 키를 가진 성공한 PG 거래. 거래 키가 다르면 결제가 충돌로 본다. */
    private fun succeeded(payment: Payment): PgTransaction {
        return createPgTransactionOf(payment, PgStatus.SUCCESS)
    }

    /** 죽은 PG에 [order]를 두 번 대사해 브레이커를 연다. 조회는 4다. */
    private fun openPgLookupBreaker(order: Order = this.order) {
        repeat(2) { orderConfirmer.reconcile(order.userId, order.id) }

        assertThat(pgLookupBreaker().state).isEqualTo(CircuitBreaker.State.OPEN)
    }

    private fun pgLookupBreaker(): CircuitBreaker {
        return circuitBreakerRegistry.circuitBreaker(LocalPgGateway.PG_LOOKUP)
    }

    /** The breaker logged exactly one WARN per pair in [transitions], in order, each naming the breaker and both states. */
    private fun assertBreakerWarnings(vararg transitions: Pair<CircuitBreaker.State, CircuitBreaker.State>) {
        val requirements = transitions.map { (from, to) ->
            ThrowingConsumer<ILoggingEvent> {
                assertThat(it.formattedMessage).contains("name=${LocalPgGateway.PG_LOOKUP}", "from=$from", "to=$to")
            }
        }

        assertThat(pgAdapterLogs.list.filter { it.level == Level.WARN }).satisfiesExactly(*requirements.toTypedArray())
    }

    /** The breaker's Micrometer state gauge for [state]: 1 while the breaker is in that state, 0 otherwise. */
    private fun pgLookupStateGauge(state: CircuitBreaker.State): Double {
        return meterRegistry.get("resilience4j.circuitbreaker.state")
            .tag("name", LocalPgGateway.PG_LOOKUP)
            .tag("state", state.name.lowercase())
            .gauge()
            .value()
    }

    /** 브레이커도 시간 초과도 결제를 실패로 끝내거나 예약을 돌려주지 않는다. 새 트랜잭션에서 [order]와 결제, [product]의 재고를 다시 읽는다. */
    private fun assertStillPending(order: Order, product: Product, stock: Int) {
        inNewTransaction {
            assertThat(orderFinder.find(order.userId, order.id).status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(paymentFinder.findByOrder(order.id).status).isEqualTo(PaymentStatus.PENDING)
            assertThat(productFinder.find(product.id).stock).isEqualTo(stock)
        }
    }

    /** PG 주문 번호 [pgOrderId]로 조회되는 [amount]원 PG 거래. 거래 키는 따로 넘기지 않으면 장면의 생성 스텁이 쓰는 [TRANSACTION_KEY]다. */
    private fun pgTransaction(
        pgOrderId: String,
        amount: Long,
        status: PgStatus,
        transactionKey: String = TRANSACTION_KEY,
    ): PgTransaction {
        return PgTransaction(transactionKey = transactionKey, pgOrderId = pgOrderId, amount = Money(amount), status = status)
    }

    /**
     * [draft]를 확정하는 TX A가 키의 사전 조건을 지나 재고를 예약한 뒤, 결제를 만들기 바로 앞에서 멈춘다. 주문과 상품의 잠금을 쥔 채다.
     * 다른 주문의 결제 시작은 멈추지 않는다.
     */
    private fun pauseBeforeStartingPaymentOf(draft: Order) {
        val target = AopTestUtils.getUltimateTargetObject<PaymentModifyService>(paymentModifyService)
        every { target.start(draft.id, any(), any()) } answers {
            pause.hold()
            callOriginal()
        }
    }

    /**
     * 같은 MySQL, Redis, Kafka와 첫 컨텍스트의 테스트 PG 위에 commerce-api를 하나 더 띄워 [block]에 넘기고, 끝나면 닫는다. 재시작한 프로세스다.
     * 운영의 애플리케이션 클래스와 test 프로필, 기반과 같은 Testcontainers 설정으로 뜨고, 컨테이너는 정적이라 같은 DB, Redis, broker다. 그 설정이
     * `DynamicPropertyRegistrar` 빈으로 넣는 접속 정보는 테스트 프레임워크 밖에서도 `spring-boot-testcontainers`의 자동 설정이 들인다.
     * 덮는 값은 명령줄 인자로 넘긴다. builder의 `properties`는 기본 속성이라 프로필의 설정 파일에 진다. test 프로필의 `ddl-auto: create`는
     * 표를 다시 만들어 첫 컨텍스트가 남긴 결제를 지우므로 `none`으로 덮는다. 운영의 재시작도 스키마를 다시 만들지 않는다. `pg.base-url`은
     * 첫 컨텍스트의 `pg.base-url`에 들어간 테스트 PG 서버의 주소라 그 스텁과 요청 기록이 그대로 남는다. 서블릿과 관리 서버는 빈 포트에 띄운다. 로깅은 JVM에 하나라 첫
     * 컨텍스트가 맡으므로 `LoggingApplicationListener`를 뺀다. 그대로 두면 두 번째 컨텍스트가 닫히며 logback을 초기화 전으로 표시하고,
     * 뒤에 뜨는 테스트 컨텍스트가 logback을 다시 설정한다. 첫 컨텍스트의 spy와 PG 주문 번호 생성기는 두 번째 컨텍스트에 없다.
     */
    private fun restarted(block: (ConfigurableApplicationContext) -> Unit) {
        val application = SpringApplicationBuilder(
            CommerceApiApplication::class.java,
            MySqlTestContainersConfig::class.java,
            RedisTestContainersConfig::class.java,
            KafkaTestContainersConfig::class.java,
        )
            .profiles("test")
            .build()
        application.setListeners(application.listeners.filterNot { it is LoggingApplicationListener })

        application.run(
            "--pg.base-url=${server.baseUrl()}",
            "--spring.jpa.hibernate.ddl-auto=none",
            "--server.port=0",
            "--management.server.port=0",
        ).use(block)
    }

    /** [stopBeforeSendingTheCreate]가 생성을 보내기 전에 던지는, 멈춘 프로세스. */
    private class ProcessStopped : RuntimeException("생성을 보내기 전에 프로세스가 멈췄다.")
}
