package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.payment.PaymentModifyService
import com.loopers.application.payment.PgTransactionService
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductListInfo
import com.loopers.application.product.provided.ProductListRequest
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentMethod
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.product.InsufficientStockException
import com.loopers.support.EnableTestPg
import com.loopers.support.Pause
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
 * 같은 키로 겨루는 두 확정은 결제를 만드는 Service를 spy해 늦은 쪽을 결제 삽입 앞에서 멈춘다.
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

    /** 같은 키로 겨루는 두 확정 가운데 하나를 결제를 만들기 앞에서 멈추는 자리([pauseBeforeStartingPaymentOf]). */
    @MockkSpyBean
    private lateinit var paymentModifyService: PaymentModifyService

    private val pause = Pause()

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

    /**
     * 같은 키로 다시 확정하면 PG를 부르지도 조회하지도 않고 그때 저장된 상태로 답한다(ADR 0025 규칙 3). 첫 확정이 생성을 보내기 전에 다시 물으면
     * 결제 중인 주문과 결과 미확인 결제이고, 첫 확정이 끝난 뒤에 다시 물으면 확정된 주문이다. 첫 응답이 아니라 지금의 상태다.
     */
    @Test
    fun `confirming again with the same key answers the stored state, pending before the first create and confirmed after it`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 5)), quantity = 2)
        val request = createOrderConfirmRequest()
        acceptNextCreate(testPg, amount = 2_000, card = CreditCard(type = request.cardType, number = request.cardNo))
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
        acceptNextCreate(testPg, amount = 2_000, card = CreditCard(type = request.cardType, number = request.cardNo))

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
        val card = CreditCard(type = request.cardType, number = request.cardNo)
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
        acceptNextCreate(testPg, amount = 2_000, card = CreditCard(type = request.cardType, number = request.cardNo))
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

    /** 키는 대소문자까지 그대로 견준다(ADR 0025 규칙 2). 대소문자만 다른 두 키는 다른 키라서 두 주문이 하나씩 써 저마다 확정된다. */
    @Test
    fun `keys that differ only in case are different keys, so two orders each confirm with one of them`() {
        val request = createOrderConfirmRequest()
        val card = CreditCard(type = request.cardType, number = request.cardNo)
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
        acceptNextCreate(testPg, amount = 2_000, card = CreditCard(type = request.cardType, number = request.cardNo))
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
}
