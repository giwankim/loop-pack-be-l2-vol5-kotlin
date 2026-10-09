package com.loopers.application.brand.provided

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.product.provided.ProductFinder
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessResourceFailureException

/**
 * 브랜드 삭제가 중간에 실패하면 브랜드와 그 상품의 변경이 함께 되돌아가는지 실제 MySQL에서 확인한다(ADR 0017).
 *
 * 실패는 저장 단계에 넣는다. [BrandRepository]를 spy해 `save`가 영속성 컨텍스트를 flush한 뒤 던지게 한다. flush가 상품과 브랜드의
 * UPDATE를 MySQL에 실제로 보내므로, 예외는 변경 SQL이 나간 뒤에 난다. 운영 코드는 flush하지 않는다.
 * 롤백된 결과를 새 트랜잭션에서 다시 읽어야 하므로 커밋하는 기반을 쓴다.
 * 저장소는 CGLIB 프록시인 Service와 달리 JDK 프록시이므로 stub은 spy에 바로 건다. 기반의 `prepare`도 이 저장소를 거치므로
 * stub은 준비를 마친 뒤에 건다.
 */
class BrandRegisterRollbackTest(
    private val brandRegister: BrandRegister,
    private val brandFinder: BrandFinder,
    private val productFinder: ProductFinder,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var brandRepository: BrandRepository

    @Test
    fun `a brand delete that fails midway rolls back the brand and both of its products`() {
        prepareBrand()
        val products = List(2) { prepareProduct(brand) }
        val failure = DataAccessResourceFailureException("브랜드를 저장하다 실패했다")
        failSavingBrandAfterFlush(failure)

        val exception = assertThrows<DataAccessResourceFailureException> { brandRegister.delete(brand.id) }

        assertThat(exception).isSameAs(failure)
        inNewTransaction {
            assertThat(brandFinder.find(brand.id).deletedAt).isNull()
            products.forEach { product ->
                assertThat(productFinder.find(product.id).deletedAt).isNull()
            }
        }
    }

    /** 브랜드의 저장이 그때까지의 변경을 flush해 MySQL에 보낸 뒤 [failure]를 던진다. */
    private fun failSavingBrandAfterFlush(failure: RuntimeException) {
        every { brandRepository.save(any()) } answers {
            entityManager.flush()
            throw failure
        }
    }
}
