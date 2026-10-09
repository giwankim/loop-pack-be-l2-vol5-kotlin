package com.loopers.application.order.provided

import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.point.PointModifyService
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.ProductModifyService
import com.loopers.application.product.provided.ProductRegister
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.Product
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.util.AopTestUtils
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 주문 확정과 상품을 삭제하는 쓰기가 같은 상품 행을 두고 엇갈릴 때를 실제 MySQL의 행 잠금으로 확인한다(ADR 0018).
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
    private val pointAccountFinder: PointAccountFinder,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        /**
         * 다른 쪽이 잠금을 기다리는지 보려고 기다리는 시간. 잠금이 없으면 이 안에 끝난다.
         * 잠금 대기를 3초로 줄여도(ADR 0018 규칙 7) 기다리는 쪽이 그 전에 풀려나도록 넉넉히 짧다.
         */
        private val LOCK_WAIT_PROBE: Duration = Duration.ofSeconds(1)

        /** 테스트가 풀지 못하고 실패해도 멈춘 쪽이 이어 가는 한도. */
        private val HOLD_LIMIT: Duration = Duration.ofSeconds(10)
    }

    @MockkSpyBean
    private lateinit var pointModifyService: PointModifyService

    @MockkSpyBean
    private lateinit var productModifyService: ProductModifyService

    private val pause = Pause()

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
            assertThat(deletedAtOf(product)).isNotNull()
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
            assertThat(deletedAtOf(product)).isNotNull()
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
            assertThat(deletedAtOf(product)).isNotNull()
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
            assertThat(deletedAtOf(product)).isNotNull()
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

    private fun deletedAtOf(product: Product): Any? {
        return entityManager
            .createNativeQuery("select deleted_at from product where id = :id")
            .setParameter("id", product.id)
            .singleResult
    }

    /**
     * 잠금을 쥔 쪽을 멈춰 두는 latch. 멈출 단계의 stub이 [hold]를 부르고, 테스트는 [awaitHeld]로 멈춘 것을 확인한 뒤
     * 다른 쪽을 부르고 [release]로 푼다. 테스트가 풀기 전에 실패해도 [hold]는 [HOLD_LIMIT] 뒤에 이어 가므로 기반 클래스의 정리가 끝난다.
     */
    private class Pause {
        private val held = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun hold() {
            held.countDown()
            released.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
        }

        fun awaitHeld() {
            assertThat(held.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)).describedAs("멈출 단계에 닿았다").isTrue()
        }

        fun release() {
            released.countDown()
        }
    }
}
