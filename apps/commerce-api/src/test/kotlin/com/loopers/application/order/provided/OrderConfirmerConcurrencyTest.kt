package com.loopers.application.order.provided

import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.point.PointModifyService
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.ProductModifyService
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductRegister
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.InsufficientStockException
import com.loopers.domain.product.Product
import com.loopers.support.Pause
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

/**
 * 같은 재고를 두고 겹치는 확정과, 확정·상품 삭제가 엇갈릴 때를 실제 MySQL의 행 잠금으로 확인한다(ADR 0018).
 * 재고 경합은 start gate로 확정을 함께 풀고, 결과를 성공·업무 거절·기술 오류로 센다. 그다음 새 트랜잭션에서 읽은
 * 주문·품목·재고·잔액으로 수량과 잔액의 식을 계산해 본다.
 *
 * 한쪽이 잠금을 쥔 채 [Pause]에서 멈추도록 그 Service의 단계를 spy하고, 다른 쪽을 다른 스레드에서 부른다.
 * 다른 쪽이 [LOCK_WAIT_PROBE] 뒤에도 끝나지 않았으면 잠금을 기다리는 것이다. 멈춘 쪽을 풀고 나서 두 결과와 새 트랜잭션에서 읽은
 * 최종 상태를 본다. Service는 CGLIB 프록시이므로 springmockk 문서대로 프록시가 감싼 spy에 stub한다.
 */
class OrderConfirmerConcurrencyTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderFinder: OrderFinder,
    private val brandRegister: BrandRegister,
    private val productRegister: ProductRegister,
    private val productFinder: ProductFinder,
    private val pointAccountFinder: PointAccountFinder,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var pointModifyService: PointModifyService

    @MockkSpyBean
    private lateinit var productModifyService: ProductModifyService

    private val pause = Pause()

    /**
     * 수량의 식은 처음 재고 − 확정된 주문의 품목 수량 = 최종 재고다. 이 경쟁에는 충전이 없으므로 잔액의 식은
     * 처음 잔액 − 확정된 주문의 결제액 = 최종 잔액이고, 구매자마다 계정이 따로라 계정마다 본다.
     */
    @Test
    fun `eight concurrent confirmations for five units confirm five orders and leave three drafts with unchanged balances`() {
        val initialStock = 5
        val initialBalance = 10_000L
        prepareProduct(price = 1_000, stock = initialStock)
        val drafts = List(8) {
            val owner = prepareUser()
            charge(amount = initialBalance, user = owner)
            prepareOrder(user = owner, products = listOf(product), quantity = 1)
        }

        val results = runConcurrently(drafts.map { draft -> { orderConfirmer.confirm(draft.userId, draft.id) } })

        val outcomes = tally(results, InsufficientStockException::class)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 5, rejections = 3, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(drafts.size)
        inNewTransaction {
            val reread = drafts.associate { draft -> draft.id to orderFinder.find(draft.userId, draft.id) }
            val confirmed = reread.values.filter { persisted -> persisted.status == OrderStatus.CONFIRMED }
            val confirmedItems = confirmed.flatMap { persisted -> persisted.items }
            val soldQuantity = confirmedItems.filter { item -> item.productId == product.id }.sumOf { item -> item.quantity }
            val finalStock = productFinder.find(product.id).stock
            assertThat(finalStock).isEqualTo(initialStock - soldQuantity)
            assertThat(finalStock).isZero()
            drafts.forEach { draft ->
                val confirmedByOwner = confirmed.filter { persisted -> persisted.userId == draft.userId }
                val paid = confirmedByOwner.sumOf { persisted -> persisted.paidAmount!!.amount }
                val finalBalance = pointAccountFinder.findByUser(draft.userId).balance.amount
                assertThat(finalBalance).isEqualTo(initialBalance - paid)
            }
            drafts.zip(results).forEach { (draft, result) ->
                val persisted = reread.getValue(draft.id)
                if (result.isSuccess) {
                    assertThat(result.getOrThrow().status).isEqualTo(OrderStatus.CONFIRMED)
                    assertThat(persisted.status).isEqualTo(OrderStatus.CONFIRMED)
                    assertThat(persisted.paidAmount).isEqualTo(draft.totalAmount)
                    assertThat(persisted.confirmedAt).isNotNull()
                } else {
                    assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
                    assertThat(persisted.paidAmount).isNull()
                    assertThat(persisted.confirmedAt).isNull()
                    assertThat(persisted.updatedAt).isEqualTo(draft.updatedAt)
                }
            }
        }
    }

    @Test
    fun `deleting a brand while a confirmation holds its product waits, then deletes the product keeping the deduction`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        charge(amount = 3_000)
        pauseConfirmationAtPointDeduction()

        val confirming = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }
        pause.awaitHeld()
        val deleting = inAnotherThread { brandRegister.delete(brand.id) }

        assertThat(deleting.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        assertThat(confirming.await().status).isEqualTo(OrderStatus.CONFIRMED)
        deleting.await()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isZero()
            assertThat(stockOf(product)).isEqualTo(7)
            assertThat(entityManager.productDeletedAt(product.id)).isNotNull()
        }
    }

    @Test
    fun `deleting a product while a confirmation holds it waits, then deletes the product keeping the deduction`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        charge(amount = 3_000)
        pauseConfirmationAtPointDeduction()

        val confirming = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }
        pause.awaitHeld()
        val deleting = inAnotherThread { productRegister.delete(product.id) }

        assertThat(deleting.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        assertThat(confirming.await().status).isEqualTo(OrderStatus.CONFIRMED)
        deleting.await()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isZero()
            assertThat(stockOf(product)).isEqualTo(7)
            assertThat(entityManager.productDeletedAt(product.id)).isNotNull()
        }
    }

    /** 잠금 읽기는 기다린 뒤 가장 최근에 커밋된 행을 읽으므로, 그 사이 커밋된 삭제를 보고 확정을 거절한다. */
    @Test
    fun `confirming while a brand delete holds the product waits, then is rejected as not available and changes nothing`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        charge(amount = 3_000)
        pauseBrandDeleteAfterItsProducts()

        val deleting = inAnotherThread { brandRegister.delete(brand.id) }
        pause.awaitHeld()
        val confirming = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }

        assertThat(confirming.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { confirming.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.DRAFT)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(3_000L)
            assertThat(stockOf(product)).isEqualTo(10)
            assertThat(entityManager.productDeletedAt(product.id)).isNotNull()
        }
    }

    @Test
    fun `confirming while a product delete holds the product waits, then is rejected as not available and changes nothing`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        charge(amount = 3_000)
        pauseProductDeleteAfterDeleting()

        val deleting = inAnotherThread { productRegister.delete(product.id) }
        pause.awaitHeld()
        val confirming = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }

        assertThat(confirming.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        deleting.await()
        val exception = assertThrows<CoreException> { confirming.await() }
        assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.DRAFT)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(3_000L)
            assertThat(stockOf(product)).isEqualTo(10)
            assertThat(entityManager.productDeletedAt(product.id)).isNotNull()
        }
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

    /** 확정이 모든 품목의 상품을 읽고 재고를 차감한 뒤, 포인트를 차감하기 전에 멈춘다. */
    private fun pauseConfirmationAtPointDeduction() {
        val target = AopTestUtils.getUltimateTargetObject<PointModifyService>(pointModifyService)
        every { target.deduct(any(), any()) } answers {
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
