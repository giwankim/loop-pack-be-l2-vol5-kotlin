package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.payment.PgTransactionService
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductListInfo
import com.loopers.application.product.provided.ProductListRequest
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
import com.loopers.support.productDeletedAt
import com.loopers.support.productStock
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.wiremock.spring.InjectWireMock

/**
 * 고객의 확정이 PG로 결제하는 장면을 실제 MySQL과 테스트 PG로 확인한다(ADR 0024, 0028). 과제의 명령
 * `--tests '*PaymentIntegrationTest'`가 이 클래스를 찾는다.
 *
 * 커밋하는 기반을 쓰므로 TX A와 TX B가 정말 커밋하고, 실제 어댑터가 HTTP로 테스트 PG에 닿는다. 장면마다 PG가 받은 생성 수를 세고,
 * 주문·결제·재고·잔액을 새 트랜잭션에서 다시 읽는다. 결제가 받을 PG 주문 번호는 확정 전에 `nextPgOrderId`로 정해 생성 스텁에 넣는다.
 * 결제 중인 주문 곁의 쓰기와 읽기(관리자의 재고 수정, 상품 삭제, 예약된 재고, 고객 목록)는 PG 호출을 spy해 생성을 보내기 바로 앞에 넣는다.
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

    /** 결제 중인 주문 곁의 쓰기와 읽기를 넣는 자리([whilePaymentPending]). stub이 없으면 실제 PG 호출 그대로다. */
    @MockkSpyBean
    private lateinit var pgTransactionService: PgTransactionService

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
            card = CreditCard(type = request.cardType, number = request.cardNo),
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
            card = CreditCard(type = request.cardType, number = request.cardNo),
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
            card = CreditCard(type = request.cardType, number = request.cardNo),
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
            card = CreditCard(type = request.cardType, number = request.cardNo),
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
            card = CreditCard(type = request.cardType, number = request.cardNo),
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
            card = CreditCard(type = request.cardType, number = request.cardNo),
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
}
