package com.loopers.application.product.provided

import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.loopers.support.withStatistics
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessResourceFailureException

/**
 * 목록을 바꿨다고 표시한 쓰기가 변경 SQL을 보낸 뒤 실패하면, revision도 캐시된 목록도 그대로인지 실제 MySQL·Redis에서 확인한다(ADR 0020).
 * 올리기는 `beforeCommit`에서 일어나므로 되돌아가는 트랜잭션은 올리지 않는다. 그래서 캐시는 한 번도 커밋되지 않은 상태를 보이지 않는다.
 *
 * 실패는 [com.loopers.application.order.provided.OrderConfirmerRollbackTest]와
 * [com.loopers.application.brand.provided.BrandRegisterRollbackTest]처럼 저장 단계에 넣는다. 저장소를 spy해 `save`가 영속성 컨텍스트를
 * flush한 뒤 던지게 하므로, 예외는 표시와 변경 SQL이 모두 나간 뒤에 난다. 운영 코드는 flush하지 않는다.
 * 기반의 `prepare`도 이 저장소들을 거치므로 stub은 준비와 캐시 채우기를 마친 뒤에 건다.
 * 확정은 TX A 안에서 실패해 PG를 부르지 않으므로 테스트 PG를 띄우지 않는다.
 */
class ProductCacheRollbackTest(
    private val orderConfirmer: OrderConfirmer,
    private val productFinder: ProductFinder,
    private val productRegister: ProductRegister,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var orderRepository: OrderRepository

    @MockkSpyBean
    private lateinit var productRepository: ProductRepository

    /** 마지막 하나를 예약하는 확정이 재고 예약의 표시 뒤, TX A의 주문 저장에서 실패한다. 품절은 커밋된 적이 없으므로 목록에 보이지 않는다. */
    @Test
    fun `a sold out confirmation failing after its SQL leaves the revision and the cached list as they were`() {
        prepareOrder(products = listOf(prepareProduct(stock = 1)), quantity = 1)
        val request = ProductListRequest()
        val cached = productFinder.findAll(request)
        assertThat(cached.items.map { it.soldOut }).containsExactly(false)
        val revision = currentListRevision()
        val failure = DataAccessResourceFailureException("주문을 저장하다 실패했다")
        failSavingOrderAfterFlush(failure)

        val exception = assertThrows<DataAccessResourceFailureException> {
            orderConfirmer.confirm(user.id, order.id, "sold-out-key", createOrderConfirmRequest())
        }

        assertThat(exception).isSameAs(failure)
        assertThat(currentListRevision()).isEqualTo(revision)
        assertListIsHitWith(request, cached)
    }

    /** 삭제가 표시 뒤, 상품 저장에서 실패한다. 삭제는 커밋된 적이 없으므로 상품은 목록에 그대로 있다. */
    @Test
    fun `a product delete failing after its SQL leaves the revision and the cached list as they were`() {
        prepareProduct()
        val request = ProductListRequest()
        val cached = productFinder.findAll(request)
        assertThat(cached.items.map { it.id }).containsExactly(product.id)
        val revision = currentListRevision()
        val failure = DataAccessResourceFailureException("상품을 저장하다 실패했다")
        failSavingProductAfterFlush(failure)

        val exception = assertThrows<DataAccessResourceFailureException> { productRegister.delete(product.id) }

        assertThat(exception).isSameAs(failure)
        assertThat(currentListRevision()).isEqualTo(revision)
        assertListIsHitWith(request, cached)
    }

    /** 다음 읽기가 revision 하나만 읽는 hit이고, 실패 전에 캐시한 [cached]와 같은지 본다. */
    private fun assertListIsHitWith(request: ProductListRequest, cached: ProductListInfo) {
        entityManager.withStatistics { statistics ->
            val reread = productFinder.findAll(request)

            assertThat(reread).isEqualTo(cached)
            assertThat(statistics.prepareStatementCount).isEqualTo(1L)
        }
    }

    /** 주문의 저장이 그때까지의 변경을 flush해 MySQL에 보낸 뒤 [failure]를 던진다. */
    private fun failSavingOrderAfterFlush(failure: RuntimeException) {
        every { orderRepository.save(any()) } answers {
            entityManager.flush()
            throw failure
        }
    }

    /** 상품의 저장이 그때까지의 변경을 flush해 MySQL에 보낸 뒤 [failure]를 던진다. */
    private fun failSavingProductAfterFlush(failure: RuntimeException) {
        every { productRepository.save(any()) } answers {
            entityManager.flush()
            throw failure
        }
    }
}
