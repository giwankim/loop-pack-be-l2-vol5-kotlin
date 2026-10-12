package com.loopers.application.order.provided

import com.loopers.application.order.required.OrderRepository
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.shared.Money
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessResourceFailureException

/**
 * 주문 확정이 변경 SQL을 보낸 뒤 실패하면 두 상품의 재고, 포인트 잔액, 주문의 확정이 함께 되돌아가는지 실제 MySQL에서 확인한다(ADR 0003).
 *
 * 실패는 확정의 마지막 단계인 주문 저장에 넣는다. [OrderRepository]를 spy해 `save`가 영속성 컨텍스트를 flush한 뒤 던지게 한다.
 * 그 flush가 끝나면 재고·잔액·주문의 UPDATE가 모두 MySQL에 나가 있다. 첫 상품의 재고는 재고 차감이 둘째 상품을 다시 잠가 읽을 때
 * 부른 자동 flush로 먼저 나가고, 주문의 확정과 잔액, 둘째 상품의 재고는 stub의 flush가 보낸다. 그래서 예외는 확정의 변경 SQL이 다
 * 나간 뒤에 난다.
 * 운영 코드는 flush하지 않는다.
 * 롤백된 결과를 새 트랜잭션에서 다시 읽어야 하므로 커밋하는 기반을 쓴다. 저장소는 JDK 프록시이므로 stub은 spy에 바로 건다.
 * 기반의 `prepareOrder`도 이 저장소로 주문을 저장하므로 stub은 준비를 마친 뒤에 건다.
 *
 * HTTP로 보는 실패는 커밋의 마지막 UPDATE 자체를 임시 CHECK로 거절하는
 * [com.loopers.adapter.webapi.v1.order.OrderConfirmationApiTest]에 있다.
 */
class OrderConfirmerRollbackTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderFinder: OrderFinder,
    private val productFinder: ProductFinder,
    private val pointAccountFinder: PointAccountFinder,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var orderRepository: OrderRepository

    @Test
    fun `a confirmation failing at the order's save rolls back both stocks, the balance and the order, and a retry confirms`() {
        val first = prepareProduct(price = 1_000, stock = 10)
        val second = prepareProduct(price = 2_000, stock = 5)
        prepareOrder(first to 3, second to 1)
        charge(amount = 10_000)
        val failure = DataAccessResourceFailureException("주문을 저장하다 실패했다")
        failSavingOrderOnceAfterFlush(failure)

        val exception = assertThrows<DataAccessResourceFailureException> { orderConfirmer.confirm(user.id, order.id) }

        assertThat(exception).isSameAs(failure)
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(persisted.paidAmount).isNull()
            assertThat(persisted.confirmedAt).isNull()
            assertThat(persisted.updatedAt).isEqualTo(order.updatedAt)
            assertThat(productFinder.find(first.id).stock).isEqualTo(10)
            assertThat(productFinder.find(second.id).stock).isEqualTo(5)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(10_000L)
        }
        val confirmed = orderConfirmer.confirm(user.id, order.id)
        assertThat(confirmed.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(confirmed.paidAmount).isEqualTo(Money(5_000))
        inNewTransaction {
            assertThat(productFinder.find(first.id).stock).isEqualTo(7)
            assertThat(productFinder.find(second.id).stock).isEqualTo(4)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(5_000L)
        }
    }

    /** 처음 부른 주문 저장만 그때까지의 변경을 flush해 MySQL에 보낸 뒤 [failure]를 던진다. 다시 부른 저장은 그대로 지나간다. */
    private fun failSavingOrderOnceAfterFlush(failure: RuntimeException) {
        every { orderRepository.save(any()) } answers {
            entityManager.flush()
            throw failure
        } andThenAnswer {
            callOriginal()
        }
    }
}
