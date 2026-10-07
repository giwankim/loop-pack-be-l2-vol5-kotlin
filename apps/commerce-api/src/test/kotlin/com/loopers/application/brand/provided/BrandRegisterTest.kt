package com.loopers.application.brand.provided

import com.loopers.application.product.required.ProductRepository
import com.loopers.domain.brand.createBrandAdminRegisterRequest
import com.loopers.domain.brand.createBrandAdminUpdateRequest
import com.loopers.domain.product.Stock
import com.loopers.domain.product.createProduct
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.stereotype.ApplicationServiceTest
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [BrandRegister]를 실제 MySQL 위에서 확인한다. 테스트 트랜잭션이 포트의 트랜잭션을 감싸므로 테스트마다 롤백으로 정리한다([ApplicationServiceTest]).
 * 같은 트랜잭션 안에서는 영속성 컨텍스트가 조회를 가로채므로, 저장 뒤에 flush/clear를 해서 다음 조회가 SQL을 실제로 보내게 한다.
 * 결과는 같은 조각의 [BrandFinder]로 읽는다.
 *
 * 상품은 상품 유스케이스가 아니라 저장 약속으로 만든다. 다른 조각의 준비물이라, 상품 등록 규칙이 바뀌어도 브랜드 테스트는 흔들리지 않는다.
 */
@ApplicationServiceTest
class BrandRegisterTest(
    private val brandRegister: BrandRegister,
    private val brandFinder: BrandFinder,
    private val productRepository: ProductRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `registering an untaken name saves a brand that can be fetched back`() {
        val request = createBrandAdminRegisterRequest()

        val registered = brandRegister.register(request)
        entityManager.flushAndClear()

        val found = brandFinder.find(registered.id)

        assertThat(registered.name).isEqualTo(request.name)
        assertThat(found).isNotSameAs(registered)
        assertThat(found.id).isEqualTo(registered.id)
        assertThat(found.name).isEqualTo(request.name)
        assertThat(found.createdAt).isNotNull()
        assertThat(found.updatedAt).isNotNull()
    }

    @Test
    fun `registering a name that matches an existing brand throws BRAND_NAME_DUPLICATED and saves nothing`() {
        val existing = brandRegister.register(createBrandAdminRegisterRequest(name = "루퍼스"))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandRegister.register(createBrandAdminRegisterRequest(name = "루퍼스")) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED)
        assertThat(countBrands()).isOne()
        assertThat(brandFinder.find(existing.id).name).isEqualTo("루퍼스")
    }

    @Test
    fun `registering a name that differs from an existing brand only in letter case throws BRAND_NAME_DUPLICATED`() {
        brandRegister.register(createBrandAdminRegisterRequest(name = "Loopers"))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandRegister.register(createBrandAdminRegisterRequest(name = "LOOPERS")) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED)
        assertThat(countBrands()).isOne()
    }

    @Test
    fun `registering a blank name is rejected by request validation before the domain and saves nothing`() {
        val exception = assertThrows<ConstraintViolationException> {
            brandRegister.register(createBrandAdminRegisterRequest(name = "   "))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("브랜드 이름은 공백일 수 없습니다.")
        assertThat(countBrands()).isZero()
    }

    @Test
    fun `updating a brand replaces its name with the one sent`() {
        val registered = brandRegister.register(createBrandAdminRegisterRequest())
        entityManager.flushAndClear()

        brandRegister.update(registered.id, createBrandAdminUpdateRequest(name = " 무신사 "))
        entityManager.flushAndClear()

        assertThat(brandFinder.find(registered.id).name).isEqualTo(" 무신사 ")
    }

    @Test
    fun `updating to a name another active brand uses throws BRAND_NAME_DUPLICATED and keeps the old name`() {
        brandRegister.register(createBrandAdminRegisterRequest(name = "루퍼스"))
        val renamed = brandRegister.register(createBrandAdminRegisterRequest(name = "무신사"))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> {
            brandRegister.update(renamed.id, createBrandAdminUpdateRequest(name = "루퍼스"))
        }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED)
        assertThat(brandFinder.find(renamed.id).name).isEqualTo("무신사")
    }

    /** 삭제된 브랜드는 없는 브랜드이므로 그 이름은 비어 있다. 등록뿐 아니라 수정도 그 이름을 가져갈 수 있어야 한다. */
    @Test
    fun `a deleted brand frees its name for a rename`() {
        val deleted = brandRegister.register(createBrandAdminRegisterRequest(name = "루퍼스"))
        val renamed = brandRegister.register(createBrandAdminRegisterRequest())
        entityManager.flushAndClear()
        brandRegister.delete(deleted.id)
        entityManager.flushAndClear()

        brandRegister.update(renamed.id, createBrandAdminUpdateRequest(name = "루퍼스"))
        entityManager.flushAndClear()

        assertThat(brandFinder.find(renamed.id).name).isEqualTo("루퍼스")
    }

    @Test
    fun `updating a brand to its own name in a different letter case is not a duplicate`() {
        val registered = brandRegister.register(createBrandAdminRegisterRequest(name = "Loopers"))
        entityManager.flushAndClear()

        brandRegister.update(registered.id, createBrandAdminUpdateRequest(name = "LOOPERS"))
        entityManager.flushAndClear()

        assertThat(brandFinder.find(registered.id).name).isEqualTo("LOOPERS")
    }

    @Test
    fun `updating a blank name is rejected by request validation before the domain and keeps the old name`() {
        val registered = brandRegister.register(createBrandAdminRegisterRequest(name = "루퍼스"))
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            brandRegister.update(registered.id, createBrandAdminUpdateRequest(name = "   "))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("브랜드 이름은 공백일 수 없습니다.")
        assertThat(brandFinder.find(registered.id).name).isEqualTo("루퍼스")
    }

    @Test
    fun `updating an unknown brand throws BRAND_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { brandRegister.update(999L, createBrandAdminUpdateRequest()) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    @Test
    fun `a deleted brand is gone from the detail and from the list`() {
        val registered = brandRegister.register(createBrandAdminRegisterRequest())
        entityManager.flushAndClear()

        brandRegister.delete(registered.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandFinder.find(registered.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        assertThat(brandFinder.findAll(BrandAdminListRequest()).content).isEmpty()
        assertThat(countBrands()).isZero()
    }

    @Test
    fun `deleting a brand twice throws BRAND_NOT_FOUND the second time`() {
        val registered = brandRegister.register(createBrandAdminRegisterRequest())
        entityManager.flushAndClear()
        brandRegister.delete(registered.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandRegister.delete(registered.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    @Test
    fun `a deleted brand frees its name for a new brand`() {
        val registered = brandRegister.register(createBrandAdminRegisterRequest())
        entityManager.flushAndClear()
        brandRegister.delete(registered.id)
        entityManager.flushAndClear()

        val reregistered = brandRegister.register(createBrandAdminRegisterRequest(name = registered.name))

        assertThat(reregistered.id).isNotEqualTo(registered.id)
    }

    @Test
    fun `deleting a brand that still has an active product throws BRAND_HAS_PRODUCTS and keeps the brand`() {
        val brand = brandRegister.register(createBrandAdminRegisterRequest(name = "루퍼스"))
        productRepository.save(createProduct(brand))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandRegister.delete(brand.id) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_HAS_PRODUCTS)
        assertThat(countBrands()).isOne()
        assertThat(brandFinder.find(brand.id).name).isEqualTo("루퍼스")
    }

    /** 재고가 비었다고 상품이 없는 것은 아니다. 삭제 조건은 재고를 보지 않는다. */
    @Test
    fun `deleting a brand whose only product is out of stock throws BRAND_HAS_PRODUCTS`() {
        val brand = brandRegister.register(createBrandAdminRegisterRequest(name = "루퍼스"))
        productRepository.save(createProduct(brand, stock = Stock(0)))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandRegister.delete(brand.id) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_HAS_PRODUCTS)
        assertThat(countBrands()).isOne()
        assertThat(brandFinder.find(brand.id).name).isEqualTo("루퍼스")
    }

    /**
     * 삭제된 상품은 없는 상품이므로 남은 상품이 아니다. 상품을 모두 삭제하면 브랜드를 삭제할 수 있다.
     * 삭제가 행을 지우지 않고 시각만 찍는다는 것은 네이티브 조회를 가진 `BrandAdminApiMockMvcTest`가 확인한다.
     */
    @Test
    fun `deleting a brand whose products were all deleted leaves it gone from the detail`() {
        val brand = brandRegister.register(createBrandAdminRegisterRequest())
        productRepository.save(createProduct(brand)).delete()
        entityManager.flushAndClear()

        brandRegister.delete(brand.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandFinder.find(brand.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    /** 삭제 조건이 묻는 것은 남은 상품뿐이다. 상품을 가진 적 없는 브랜드는 아무것도 막지 않는다. */
    @Test
    fun `deleting a brand that never had a product leaves it gone from the detail`() {
        val brand = brandRegister.register(createBrandAdminRegisterRequest())
        entityManager.flushAndClear()

        brandRegister.delete(brand.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandFinder.find(brand.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    /** 삭제되지 않은 브랜드 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countBrands(): Long =
        entityManager
            .createQuery("select count(b) from Brand b", Long::class.java)
            .singleResult
}
