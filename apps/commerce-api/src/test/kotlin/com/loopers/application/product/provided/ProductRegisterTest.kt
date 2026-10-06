package com.loopers.application.product.provided

import com.loopers.application.brand.required.BrandRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.product.createProductAdminRegisterRequest
import com.loopers.domain.product.createProductAdminStockUpdateRequest
import com.loopers.domain.product.createProductAdminUpdateRequest
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
 * [ProductRegister]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.brand.provided.BrandRegisterTest]와 같다.
 * 결과는 같은 조각의 [ProductFinder]로 읽는다.
 *
 * 브랜드는 브랜드 유스케이스가 아니라 저장 약속으로 만든다. 다른 조각의 준비물이라, 브랜드 등록 규칙이 바뀌어도 상품 테스트는 흔들리지 않는다.
 */
@ApplicationServiceTest
class ProductRegisterTest(
    private val productRegister: ProductRegister,
    private val productFinder: ProductFinder,
    private val brandRepository: BrandRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `registering under an active brand saves a product, name as sent, that can be fetched back`() {
        val brand = brandRepository.save(createBrand(name = "루퍼스"))

        val registered = productRegister.register(
            createProductAdminRegisterRequest(brandId = brand.id, name = " 티셔츠 ", price = 12_000, stock = 7),
        )
        entityManager.flushAndClear()
        val found = productFinder.find(registered.id)

        assertThat(registered.brandId).isEqualTo(brand.id)
        assertThat(registered.name).isEqualTo(" 티셔츠 ")
        assertThat(found.id).isEqualTo(registered.id)
        assertThat(found.brandId).isEqualTo(brand.id)
        assertThat(found.brandName).isEqualTo("루퍼스")
        assertThat(found.name).isEqualTo(" 티셔츠 ")
        assertThat(found.price).isEqualTo(12_000L)
        assertThat(found.stock).isEqualTo(7)
        assertThat(found.soldOut).isFalse()
        assertThat(found.likeCount).isZero()
        assertThat(found.createdAt).isNotNull()
        assertThat(found.updatedAt).isNotNull()
    }

    @Test
    fun `registering under an unknown brand throws BRAND_NOT_FOUND and saves nothing`() {
        val exception = assertThrows<CoreException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = 999L))
        }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering under a deleted brand throws BRAND_NOT_FOUND and saves nothing`() {
        val deleted = brandRepository.save(createBrand().apply { delete() })
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = deleted.id))
        }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a price of zero is rejected by request validation before the domain and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 0))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 가격은 1원 이상이어야 합니다.")
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a blank name is rejected by request validation before the domain and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, name = "   "))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 이름은 공백일 수 없습니다.")
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a negative stock is rejected by request validation before the domain and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, stock = -1))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("재고는 0 이상이어야 합니다.")
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `updating a product changes the name as sent and the price and keeps the brand`() {
        val brand = brandRepository.save(createBrand())
        val registered = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        entityManager.flushAndClear()

        productRegister.update(registered.id, createProductAdminUpdateRequest(name = " 후드티 ", price = 25_000))
        entityManager.flushAndClear()
        val found = productFinder.find(registered.id)

        assertThat(found.name).isEqualTo(" 후드티 ")
        assertThat(found.price).isEqualTo(25_000L)
        assertThat(found.brandId).isEqualTo(brand.id)
    }

    @Test
    fun `updating the stock sets the final quantity, including zero`() {
        val brand = brandRepository.save(createBrand())
        val registered = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        entityManager.flushAndClear()

        productRegister.updateStock(registered.id, createProductAdminStockUpdateRequest(quantity = 0))
        entityManager.flushAndClear()
        val found = productFinder.find(registered.id)

        assertThat(found.stock).isZero()
        assertThat(found.soldOut).isTrue()
    }

    @Test
    fun `deleting a product makes it a product that does not exist`() {
        val brand = brandRepository.save(createBrand())
        val registered = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        entityManager.flushAndClear()

        productRegister.delete(registered.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { productFinder.find(registered.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(countProducts()).isZero()
        assertThat(deletedAtOf(registered.id)).isNotNull()
    }

    @Test
    fun `updating, setting the stock of, and deleting an unknown product all throw PRODUCT_NOT_FOUND`() {
        assertThat(errorTypeOf { productRegister.update(999L, createProductAdminUpdateRequest()) })
            .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(errorTypeOf { productRegister.updateStock(999L, createProductAdminStockUpdateRequest()) })
            .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(errorTypeOf { productRegister.delete(999L) }).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    @Test
    fun `updating, setting the stock of, and deleting a deleted product all throw PRODUCT_NOT_FOUND`() {
        val brand = brandRepository.save(createBrand())
        val deleted = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        productRegister.delete(deleted.id)
        entityManager.flushAndClear()

        assertThat(errorTypeOf { productRegister.update(deleted.id, createProductAdminUpdateRequest()) })
            .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(errorTypeOf { productRegister.updateStock(deleted.id, createProductAdminStockUpdateRequest()) })
            .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(errorTypeOf { productRegister.delete(deleted.id) }).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    @Test
    fun `an update rejected by request validation keeps the stored name and price`() {
        val brand = brandRepository.save(createBrand())
        val registered =
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000))
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.update(registered.id, createProductAdminUpdateRequest(price = 0))
        }
        entityManager.flushAndClear()
        val found = productFinder.find(registered.id)

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 가격은 1원 이상이어야 합니다.")
        assertThat(found.name).isEqualTo("티셔츠")
        assertThat(found.price).isEqualTo(12_000L)
    }

    @Test
    fun `a negative stock is rejected by request validation and keeps the stored stock`() {
        val brand = brandRepository.save(createBrand())
        val registered = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, stock = 7))
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.updateStock(registered.id, createProductAdminStockUpdateRequest(quantity = -1))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("재고는 0 이상이어야 합니다.")
        assertThat(productFinder.find(registered.id).stock).isEqualTo(7)
    }

    /** 거절에 실린 [ErrorType]. 세 가지 쓰기가 모두 같은 규칙을 쓰므로 한 자리에 모은다. */
    private fun errorTypeOf(call: () -> Unit): ErrorType =
        assertThrows<CoreException> { call() }.errorType

    /** 논리 삭제는 행을 지우지 않으므로 삭제 시각은 SQL 제한을 지나는 native 조회로만 볼 수 있다. */
    private fun deletedAtOf(id: Long): Any? =
        entityManager
            .createNativeQuery("select deleted_at from product where id = :id")
            .setParameter("id", id)
            .singleResult

    /** 삭제되지 않은 상품 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countProducts(): Long =
        entityManager
            .createQuery("select count(p) from Product p", Long::class.java)
            .singleResult
}
