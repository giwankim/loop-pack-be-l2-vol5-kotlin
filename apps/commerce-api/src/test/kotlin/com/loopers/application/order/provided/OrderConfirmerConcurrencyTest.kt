package com.loopers.application.order.provided

import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.point.PointModifyService
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.point.provided.PointCharger
import com.loopers.application.product.ProductModifyService
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductRegister
import com.loopers.domain.order.OrderAlreadyConfirmedException
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.point.InsufficientPointsException
import com.loopers.domain.point.createPointChargeRequest
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
 * 같은 재고를 두고 겹치는 확정, 같은 주문의 겹치는 확정, 같은 포인트 계정을 두고 겹치는 확정과 충전, 확정·상품 삭제가 엇갈릴 때를
 * 실제 MySQL의 행 잠금으로 확인한다(ADR 0018, 0019). 재고와 포인트 계정의 경합, 같은 주문의 확정, 충전과 결제는 start gate로 함께 풀고,
 * 결과를 성공·업무 거절·기술 오류로 센다. 그다음 새 트랜잭션에서 읽은 주문·품목·재고·잔액으로 수량과 잔액의 식을 계산해 본다.
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
    private val pointCharger: PointCharger,
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

    /**
     * 같은 주문의 확정이 함께 풀려도 주문 행의 잠금을 먼저 얻은 하나만 확정한다(ADR 0019). 나머지는 잠금을 기다린 뒤 확정된 주문을 읽고
     * 차감 없이 거절된다. 재고와 잔액은 다섯 번 모두 차감할 만큼 준비하므로, 주문을 잠그지 않으면 거절 대신 차감이 거듭되어 드러난다.
     */
    @Test
    fun `five concurrent confirmations of one draft confirm it once and reject the rest as already confirmed`() {
        val initialStock = 10
        val initialBalance = 5_000L
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = initialStock)), quantity = 1)
        charge(amount = initialBalance)

        val results = runConcurrently(List(5) { { orderConfirmer.confirm(user.id, order.id) } })

        val outcomes = tally(results, OrderAlreadyConfirmedException::class)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 1, rejections = 4, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(results.size)
        val confirmed = results.single { result -> result.isSuccess }.getOrThrow()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(persisted.paidAmount).isEqualTo(order.totalAmount)
            assertThat(persisted.confirmedAt).isEqualTo(confirmed.confirmedAt)
            val soldQuantity = persisted.items.sumOf { item -> item.quantity }
            assertThat(productFinder.find(product.id).stock).isEqualTo(initialStock - soldQuantity)
            val paid = persisted.paidAmount!!.amount
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(initialBalance - paid)
        }
    }

    /**
     * 같은 사용자의 확정 셋이 서로 다른 상품을 사므로 상품 잠금에서는 줄 서지 않고 포인트 계정의 잠금에서 겨룬다(ADR 0019).
     * 한 상품을 함께 사면 상품 잠금이 확정을 먼저 줄 세워 계정 잠금에서는 겨루지 않는다. 그때도 계정을 잠그지 않으면 테스트는 실패하지만,
     * 기다린 확정의 계정 읽기가 앞선 확정이 커밋하기 전의 스냅샷을 읽기 때문이라 계정 잠금의 경쟁을 보는 것이 아니다.
     * 잔액의 식은 처음 잔액 − 확정된 주문의 결제액 = 최종 잔액이고, 수량의 식은 상품마다 본다.
     */
    @Test
    fun `three concurrent confirmations of different products on one account confirm two and reject one for lack of points`() {
        val initialBalance = 10_000L
        prepareUser()
        charge(amount = initialBalance)
        val products = List(3) { prepareProduct(price = 4_000) }
        val drafts = products.map { ordered -> prepareOrder(user = user, products = listOf(ordered), quantity = 1) }

        val results = runConcurrently(drafts.map { draft -> { orderConfirmer.confirm(draft.userId, draft.id) } })

        val outcomes = tally(results, InsufficientPointsException::class)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 2, rejections = 1, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(drafts.size)
        val rejectedDraft = drafts.zip(results).single { (_, result) -> result.isFailure }.first
        inNewTransaction {
            val reread = drafts.map { draft -> orderFinder.find(draft.userId, draft.id) }
            val confirmed = reread.filter { persisted -> persisted.status == OrderStatus.CONFIRMED }
            val paid = confirmed.sumOf { persisted -> persisted.paidAmount!!.amount }
            val finalBalance = pointAccountFinder.findByUser(user.id).balance.amount
            assertThat(finalBalance).isEqualTo(initialBalance - paid)
            assertThat(finalBalance).isEqualTo(2_000L)
            val confirmedItems = confirmed.flatMap { persisted -> persisted.items }
            products.forEach { ordered ->
                val soldQuantity = confirmedItems.filter { item -> item.productId == ordered.id }.sumOf { item -> item.quantity }
                assertThat(productFinder.find(ordered.id).stock).isEqualTo(ordered.stock - soldQuantity)
            }
            val rejected = reread.single { persisted -> persisted.id == rejectedDraft.id }
            assertThat(rejected.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(rejected.paidAmount).isNull()
            assertThat(rejected.confirmedAt).isNull()
            assertThat(rejected.updatedAt).isEqualTo(rejectedDraft.updatedAt)
            val rejectedProduct = products.single { ordered -> ordered.id == rejected.items.single().productId }
            assertThat(productFinder.find(rejectedProduct.id).stock).isEqualTo(rejectedProduct.stock)
        }
    }

    /**
     * 충전과 확정은 계정 행의 잠금에서 차례로 지나가므로, 잔액의 식은 처음 잔액 + 충전액 − 확정된 주문의 결제액 = 최종 잔액이다.
     * 계정을 잠그지 않으면 나중에 커밋한 쪽이 앞의 변경을 덮어 충전이나 결제가 사라진다.
     */
    @Test
    fun `a charge and a confirmation released together on one account both succeed and both reach the balance`() {
        val initialBalance = 10_000L
        val chargeAmount = 2_000L
        prepareOrder(products = listOf(prepareProduct(price = 7_000)), quantity = 1)
        charge(amount = initialBalance)

        val results = runConcurrently(
            listOf(
                { pointCharger.charge(user.id, createPointChargeRequest(amount = chargeAmount)) },
                { orderConfirmer.confirm(user.id, order.id) },
            ),
        )

        val outcomes = tally(results)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 2, rejections = 0, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(results.size)
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.CONFIRMED)
            val paid = persisted.paidAmount!!.amount
            val finalBalance = pointAccountFinder.findByUser(user.id).balance.amount
            assertThat(finalBalance).isEqualTo(initialBalance + chargeAmount - paid)
            assertThat(finalBalance).isEqualTo(5_000L)
            val soldQuantity = persisted.items.sumOf { item -> item.quantity }
            assertThat(productFinder.find(product.id).stock).isEqualTo(product.stock - soldQuantity)
        }
    }

    /**
     * 멈춘 확정은 주문과 상품을 잠근 채다. 같은 주문의 둘째 확정은 주문 잠금에서 기다리다, 풀린 뒤 확정된 주문을 읽고 거절된다.
     * 잔액은 두 번 결제할 만큼 충전하므로, 주문을 잠그지 않으면 둘째 확정도 성공해 재고와 잔액이 두 번 줄어든다.
     */
    @Test
    fun `confirming an order held by another confirmation waits, then is rejected as already confirmed and deducts nothing`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        charge(amount = 6_000)
        pauseConfirmationBeforePointDeduction()

        val first = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }
        pause.awaitHeld()
        val second = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }

        assertThat(second.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        assertThat(first.await().status).isEqualTo(OrderStatus.CONFIRMED)
        assertThrows<OrderAlreadyConfirmedException> { second.await() }
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(3_000L)
            assertThat(productFinder.find(product.id).stock).isEqualTo(7)
        }
    }

    /**
     * 멈춘 충전은 계정을 잠근 채다. 확정은 주문과 상품을 잠가 재고를 차감한 뒤 계정 잠금에서 기다리다, 풀린 뒤 충전이 커밋한 잔액에서 결제한다.
     * 잔액은 10,000 + 2,000 − 7,000원이다. 계정을 잠그지 않으면 확정이 충전 전의 잔액에서 결제하고, 나중에 커밋한 쪽이 앞의 변경을 덮는다.
     */
    @Test
    fun `confirming while a charge holds the point account waits, then pays from the charged balance`() {
        prepareOrder(products = listOf(prepareProduct(price = 7_000)), quantity = 1)
        charge(amount = 10_000)
        pauseChargeAfterCharging()

        val charging = inAnotherThread { pointCharger.charge(user.id, createPointChargeRequest(amount = 2_000)) }
        pause.awaitHeld()
        val confirming = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }

        assertThat(confirming.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        charging.await()
        assertThat(confirming.await().status).isEqualTo(OrderStatus.CONFIRMED)
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(5_000L)
        }
    }

    /**
     * 멈춘 확정은 주문, 상품, 계정을 잠근 채다. 충전은 계정 잠금에서 기다리다, 풀린 뒤 결제가 커밋한 잔액에 더한다.
     * 잔액은 10,000 − 7,000 + 2,000원이다. 계정을 잠그지 않으면 충전이 결제 전의 잔액에 더하고, 나중에 커밋한 쪽이 앞의 변경을 덮는다.
     */
    @Test
    fun `charging while a confirmation holds the point account waits, then adds to the balance left after payment`() {
        prepareOrder(products = listOf(prepareProduct(price = 7_000)), quantity = 1)
        charge(amount = 10_000)
        pauseConfirmationAfterPointDeduction()

        val confirming = inAnotherThread { orderConfirmer.confirm(user.id, order.id) }
        pause.awaitHeld()
        val charging = inAnotherThread { pointCharger.charge(user.id, createPointChargeRequest(amount = 2_000)) }

        assertThat(charging.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        assertThat(confirming.await().status).isEqualTo(OrderStatus.CONFIRMED)
        charging.await()
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(5_000L)
        }
    }

    @Test
    fun `deleting a brand while a confirmation holds its product waits, then deletes the product keeping the deduction`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        charge(amount = 3_000)
        pauseConfirmationBeforePointDeduction()

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
        pauseConfirmationBeforePointDeduction()

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
    private fun pauseConfirmationBeforePointDeduction() {
        val target = AopTestUtils.getUltimateTargetObject<PointModifyService>(pointModifyService)
        every { target.deduct(any(), any()) } answers {
            pause.hold()
            callOriginal()
        }
    }

    /** 확정이 계정을 잠가 포인트를 차감한 뒤, 커밋하기 전에 멈춘다. [pauseConfirmationBeforePointDeduction]은 계정을 잠그기 전에 멈춘다. */
    private fun pauseConfirmationAfterPointDeduction() {
        val target = AopTestUtils.getUltimateTargetObject<PointModifyService>(pointModifyService)
        every { target.deduct(any(), any()) } answers {
            callOriginal()
            pause.hold()
        }
    }

    /** 충전이 계정을 잠가 잔액을 더한 뒤, 커밋하기 전에 멈춘다. */
    private fun pauseChargeAfterCharging() {
        val target = AopTestUtils.getUltimateTargetObject<PointModifyService>(pointModifyService)
        every { target.charge(any(), any()) } answers {
            val charged = callOriginal()
            pause.hold()
            charged
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
