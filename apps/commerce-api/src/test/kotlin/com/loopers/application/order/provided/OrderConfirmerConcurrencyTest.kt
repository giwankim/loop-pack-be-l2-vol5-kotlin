package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.payment.PaymentModifyService
import com.loopers.application.product.ProductModifyService
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductRegister
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderAlreadyConfirmedException
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.product.InsufficientStockException
import com.loopers.domain.product.Product
import com.loopers.support.EnableTestPg
import com.loopers.support.Pause
import com.loopers.support.TestPg
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.productDeletedAt
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.util.AopTestUtils
import org.wiremock.spring.InjectWireMock
import java.util.UUID

/**
 * 같은 재고를 두고 겹치는 확정, 같은 주문의 겹치는 확정, 확정·상품 삭제가 엇갈릴 때를 실제 MySQL의 행 잠금과 테스트 PG로 확인한다
 * (ADR 0018, 0019, ADR 0028 규칙 4). 재고의 경합과 같은 주문의 확정은 start gate로 함께 풀고, 결과를 성공·업무 거절·기술 오류로 센다.
 * 그다음 새 트랜잭션에서 읽은 주문·품목·재고로 수량의 식을 계산해 본다. 재고를 예약한 확정만 PG에 생성을 하나씩 보낸다.
 *
 * 한쪽이 잠금을 쥔 채 [Pause]에서 멈추도록 그 Service의 단계를 spy하고, 다른 쪽을 다른 스레드에서 부른다.
 * 다른 쪽이 [LOCK_WAIT_PROBE] 뒤에도 끝나지 않았으면 잠금을 기다리는 것이다. 멈춘 쪽을 풀고 나서 두 결과와 새 트랜잭션에서 읽은
 * 최종 상태를 본다. Service는 CGLIB 프록시이므로 springmockk 문서대로 프록시가 감싼 spy에 stub한다.
 * 확정은 TX A에서 주문과 상품을 잠근 뒤 결제를 만들기 전에 멈춘다. 그 잠금은 TX A가 커밋할 때 풀리고, PG 호출과 TX B는 그 뒤다.
 */
@EnableTestPg
class OrderConfirmerConcurrencyTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderFinder: OrderFinder,
    private val brandRegister: BrandRegister,
    private val productRegister: ProductRegister,
    private val productFinder: ProductFinder,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var paymentModifyService: PaymentModifyService

    @MockkSpyBean
    private lateinit var productModifyService: ProductModifyService

    /** 생성자 주입의 하나뿐인 예외다. 까닭은 [OrderPaymentIntegrationTest]의 같은 필드에 있다. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    private val pause = Pause()

    /** 모든 확정이 같은 카드로 결제한다. 생성 스텁은 이 카드를 맞춘다. */
    private val request = createOrderConfirmRequest()

    /**
     * 수량의 식은 처음 재고 − 확정된 주문의 품목 수량 = 최종 재고다. 다섯 주문의 금액이 같으므로 어느 결제가 어느 PG 주문 번호를 가져가도
     * 생성 스텁과 맞는다. 재고 부족으로 거절된 확정은 결제를 만들지 않아 번호도 가져가지 않고 PG에도 보내지 않는다.
     */
    @Test
    fun `eight concurrent confirmations for five units confirm five orders through five creates and leave three drafts`() {
        val initialStock = 5
        prepareProduct(price = 1_000, stock = initialStock)
        val drafts = List(8) { prepareOrder(user = prepareUser(), products = listOf(product), quantity = 1) }
        repeat(initialStock) { acceptSuccessfulCreate(amount = 1_000) }

        val results = runConcurrently(drafts.map { draft -> { confirm(draft) } })

        val outcomes = tally(results, InsufficientStockException::class)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 5, rejections = 3, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(drafts.size)
        assertThat(testPg.createCount()).isEqualTo(outcomes.successes)
        inNewTransaction {
            val reread = drafts.associate { draft -> draft.id to orderFinder.find(draft.userId, draft.id) }
            val confirmed = reread.values.filter { persisted -> persisted.status == OrderStatus.CONFIRMED }
            val confirmedItems = confirmed.flatMap { persisted -> persisted.lineItems }
            val soldQuantity = confirmedItems.filter { item -> item.productId == product.id }.sumOf { item -> item.quantity }
            val finalStock = productFinder.find(product.id).stock
            assertThat(finalStock).isEqualTo(initialStock - soldQuantity)
            assertThat(finalStock).isZero()
            drafts.zip(results).forEach { (draft, result) ->
                val persisted = reread.getValue(draft.id)
                if (result.isSuccess) {
                    assertThat(result.getOrThrow().order.status).isEqualTo(OrderStatus.CONFIRMED)
                    assertThat(persisted.status).isEqualTo(OrderStatus.CONFIRMED)
                    assertThat(persisted.paidAmount).isEqualTo(draft.totalAmount)
                    assertThat(persisted.confirmedAt).isNotNull()
                } else {
                    assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
                    assertThat(persisted.paymentMethod).isNull()
                    assertThat(persisted.updatedAt).isEqualTo(draft.updatedAt)
                }
            }
        }
    }

    /**
     * 같은 주문의 확정이 함께 풀려도 주문 행의 잠금을 먼저 얻은 하나만 재고를 예약하고 결제를 만든다(ADR 0019, ADR 0024 규칙 2).
     * 나머지는 잠금을 기다린 뒤 결제를 시작한 주문을 읽고 예약 없이 거절된다. 재고는 다섯 번 모두 예약할 만큼 준비하므로,
     * 주문을 잠그지 않으면 거절 대신 예약과 생성이 거듭되어 드러난다.
     */
    @Test
    fun `five concurrent confirmations of one draft reserve once, send one create and reject the rest as already confirmed`() {
        val initialStock = 10
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = initialStock)), quantity = 1)
        acceptSuccessfulCreate(amount = 1_000)

        val results = runConcurrently(List(5) { { confirm(order) } })

        val outcomes = tally(results, OrderAlreadyConfirmedException::class)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 1, rejections = 4, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(results.size)
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.CONFIRMED)
            val soldQuantity = persisted.lineItems.sumOf { item -> item.quantity }
            assertThat(productFinder.find(product.id).stock).isEqualTo(initialStock - soldQuantity)
        }
    }

    /**
     * 멈춘 확정은 주문과 상품을 잠근 채다. 같은 주문의 둘째 확정은 주문 잠금에서 기다리다, 풀린 뒤 결제를 시작한 주문을 읽고 거절된다.
     * 주문을 잠그지 않으면 둘째 확정도 재고를 예약해 재고가 두 번 줄고 결제가 둘이 된다.
     */
    @Test
    fun `confirming an order held by another confirmation waits, then is rejected as already confirmed and reserves nothing`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        acceptSuccessfulCreate(amount = 3_000)
        pauseConfirmationBeforeCreatingPayment()

        val first = inAnotherThread { confirm(order) }
        pause.awaitHeld()
        val second = inAnotherThread { confirm(order) }

        assertThat(second.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        assertThat(first.await().order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThrows<OrderAlreadyConfirmedException> { second.await() }
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(productFinder.find(product.id).stock).isEqualTo(7)
        }
    }

    @Test
    fun `deleting a brand while a confirmation holds its product waits, then deletes the product keeping the reservation`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        acceptSuccessfulCreate(amount = 3_000)
        pauseConfirmationBeforeCreatingPayment()

        val confirming = inAnotherThread { confirm(order) }
        pause.awaitHeld()
        val deleting = inAnotherThread { brandRegister.delete(brand.id) }

        assertThat(deleting.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        assertThat(confirming.await().order.status).isEqualTo(OrderStatus.CONFIRMED)
        deleting.await()
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(stockOf(product)).isEqualTo(7)
            assertThat(entityManager.productDeletedAt(product.id)).isNotNull()
        }
    }

    @Test
    fun `deleting a product while a confirmation holds it waits, then deletes the product keeping the reservation`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        acceptSuccessfulCreate(amount = 3_000)
        pauseConfirmationBeforeCreatingPayment()

        val confirming = inAnotherThread { confirm(order) }
        pause.awaitHeld()
        val deleting = inAnotherThread { productRegister.delete(product.id) }

        assertThat(deleting.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        assertThat(confirming.await().order.status).isEqualTo(OrderStatus.CONFIRMED)
        deleting.await()
        assertThat(testPg.createCount()).isOne()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(stockOf(product)).isEqualTo(7)
            assertThat(entityManager.productDeletedAt(product.id)).isNotNull()
        }
    }

    /** 잠금 읽기는 기다린 뒤 가장 최근에 커밋된 행을 읽으므로, 그 사이 커밋된 삭제를 보고 PG를 부르기 전에 확정을 거절한다. */
    @Test
    fun `confirming while a brand delete holds the product waits, then is rejected as not available and changes nothing`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        pauseBrandDeleteAfterItsProducts()

        val deleting = inAnotherThread { brandRegister.delete(brand.id) }
        pause.awaitHeld()
        val confirming = inAnotherThread { confirm(order) }

        assertThat(confirming.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { confirming.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
        assertThat(testPg.createCount()).isZero()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.DRAFT)
            assertThat(stockOf(product)).isEqualTo(10)
            assertThat(entityManager.productDeletedAt(product.id)).isNotNull()
        }
    }

    @Test
    fun `confirming while a product delete holds the product waits, then is rejected as not available and changes nothing`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        pauseProductDeleteAfterDeleting()

        val deleting = inAnotherThread { productRegister.delete(product.id) }
        pause.awaitHeld()
        val confirming = inAnotherThread { confirm(order) }

        assertThat(confirming.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { confirming.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
        assertThat(testPg.createCount()).isZero()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.DRAFT)
            assertThat(stockOf(product)).isEqualTo(10)
            assertThat(entityManager.productDeletedAt(product.id)).isNotNull()
        }
    }

    /** 주문한 사용자가 [draft]를 확정한다. 확정마다 새 멱등 키다. */
    private fun confirm(draft: Order): OrderInfo {
        return orderConfirmer.confirm(draft.userId, draft.id, UUID.randomUUID().toString(), request)
    }

    /** 다음 결제의 [amount]원 생성을 성공한 PG 거래로 받아들인다. 모든 확정이 [request]의 카드로 결제한다. */
    private fun acceptSuccessfulCreate(amount: Long) {
        acceptNextCreate(testPg, amount = amount, card = CreditCard(type = request.cardType, number = request.cardNo))
    }

    /** 관리자의 상품 삭제가 상품에 삭제 시각을 찍은 뒤, 커밋하기 전에 멈춘다. */
    private fun pauseProductDeleteAfterDeleting() {
        val target = AopTestUtils.getUltimateTargetObject<ProductModifyService>(productModifyService)
        every { target.delete(any()) } answers {
            callOriginal()
            pause.hold()
        }
    }

    /** 브랜드 삭제가 브랜드와 그 상품을 잠가 삭제한 뒤, 브랜드를 저장하기 전에 멈춘다. */
    private fun pauseBrandDeleteAfterItsProducts() {
        val target = AopTestUtils.getUltimateTargetObject<ProductModifyService>(productModifyService)
        every { target.deleteAllOfBrand(any()) } answers {
            callOriginal()
            pause.hold()
        }
    }

    /** 확정의 TX A가 주문을 잠그고 모든 품목의 재고를 예약한 뒤, 결제를 만들기 전에 멈춘다. */
    private fun pauseConfirmationBeforeCreatingPayment() {
        val target = AopTestUtils.getUltimateTargetObject<PaymentModifyService>(paymentModifyService)
        every { target.start(any(), any(), any()) } answers {
            pause.hold()
            callOriginal()
        }
    }

    /** 삭제된 상품의 재고는 어느 포트로도 읽히지 않으므로 SQL 제한을 지나는 native 조회로 읽는다. */
    private fun stockOf(product: Product): Int {
        return (
            entityManager
                .createNativeQuery("select stock from product where id = :id")
                .setParameter("id", product.id)
                .singleResult as Number
        ).toInt()
    }
}
