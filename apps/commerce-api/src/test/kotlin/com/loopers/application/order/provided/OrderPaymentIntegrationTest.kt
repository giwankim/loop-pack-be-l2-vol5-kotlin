package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentMethod
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.product.InsufficientStockException
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.IllegalTransactionStateException
import org.wiremock.spring.InjectWireMock

/**
 * 고객의 확정이 PG로 결제하는 장면을 실제 MySQL과 테스트 PG로 확인한다(ADR 0024, 0028). 과제의 명령
 * `--tests '*PaymentIntegrationTest'`가 이 클래스를 찾는다.
 *
 * 커밋하는 기반을 쓰므로 TX A와 TX B가 정말 커밋하고, 실제 어댑터가 HTTP로 테스트 PG에 닿는다. 장면마다 PG가 받은 생성 수를 세고,
 * 주문·결제·재고·잔액을 새 트랜잭션에서 다시 읽는다. 결제가 받을 PG 주문 번호는 확정 전에 `nextPgOrderId`로 정해 생성 스텁에 넣는다.
 */
@EnableTestPg
class OrderPaymentIntegrationTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderFinder: OrderFinder,
    private val paymentFinder: PaymentFinder,
    private val productFinder: ProductFinder,
    private val pointAccountFinder: PointAccountFinder,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"
        private const val TRANSACTION_KEY = "20261022:TR:9577c5"
    }

    /**
     * 생성자 주입의 하나뿐인 예외다. `junit-platform.properties`의 `spring.test.constructor.autowire.mode=all` 때문에 생성자 파라미터는
     * Spring과 WireMock의 파라미터 resolver가 함께 맡으려 다투다 실패한다(탐침 보고서 §7의 1). 필드는 WireMock의 리스너가 넣는다.
     */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /** 과제 6절의 장면 1. 받아들여진 생성이 성공을 말하면 TX B가 주문을 확정하고 예약을 소비한다. 재고를 더 줄이지 않고 포인트도 쓰지 않는다. */
    @Test
    fun `confirming pays once through the PG, confirms the order on a successful transaction and keeps the reserved stock`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        charge(amount = 10_000)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = 2_000,
            card = CreditCard(type = request.cardType, number = request.cardNo),
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
     * 과제 6절의 장면 4. 생성은 PG에 닿았지만 응답을 잃었다. PG 거래가 생겼을 수도 있으므로 결제는 결과 미확인, 주문은 결제 중인 주문으로
     * 남고 예약도 그대로다(ADR 0024 규칙 4). 생성은 다시 보내지 않는다.
     */
    @Test
    fun `a create whose response is lost leaves the payment unknown, the order payment pending and the stock reserved`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.losesCreateResponse(
            pgOrderId = nextPgOrderId(),
            amount = 2_000,
            card = CreditCard(type = request.cardType, number = request.cardNo),
        )

        val pending = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(pending.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(pending.paymentStatus).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
            assertThat(persisted.confirmedAt).isNull()
            val payment = paymentFinder.findByOrder(order.id)
            assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(payment.transactionKey).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(3)
        }
    }

    /** 과제 6절의 장면 2. 받아들여진 생성은 성공이 아니다. 처리 중인 PG 거래면 결제는 처리 중, 주문은 결제 중인 주문이고 예약은 그대로다. */
    @Test
    fun `a create accepted as pending leaves the order payment pending with a pending payment and the stock reserved`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = 2_000,
            card = CreditCard(type = request.cardType, number = request.cardNo),
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
}
