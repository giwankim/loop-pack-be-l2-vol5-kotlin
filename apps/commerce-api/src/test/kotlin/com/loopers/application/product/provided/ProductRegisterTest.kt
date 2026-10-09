package com.loopers.application.product.provided

import com.loopers.domain.product.createProductAdminRegisterRequest
import com.loopers.domain.product.createProductAdminStockUpdateRequest
import com.loopers.domain.product.createProductAdminUpdateRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.errorTypeOf
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [ProductRegister]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.brand.provided.BrandRegisterTest]와 같다.
 * 결과는 같은 조각의 [ProductFinder]로 읽는다.
 */
class ProductRegisterTest(
    private val productRegister: ProductRegister,
    private val productFinder: ProductFinder,
) : BaseApplicationServiceTest() {
    @Test
    fun `registering under an active brand saves a product, name as sent, that can be fetched back`() {
        prepareBrand(name = "루퍼스")

        val registered = productRegister.register(
            createProductAdminRegisterRequest(brandId = brand.id, name = " 티셔츠 ", price = 12_000, stock = 7),
        )
        entityManager.flushAndClear()
        val found = productFinder.findInfo(registered.id)

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
        prepareBrand()
        deleteBrand()
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a price of zero is rejected by request validation before the domain and saves nothing`() {
        prepareBrand()

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 0))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 가격은 1원 이상이어야 합니다.")
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a blank name is rejected by request validation before the domain and saves nothing`() {
        prepareBrand()

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, name = "   "))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 이름은 공백일 수 없습니다.")
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a negative stock is rejected by request validation before the domain and saves nothing`() {
        prepareBrand()

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, stock = -1))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("재고는 0 이상이어야 합니다.")
        assertThat(countProducts()).isZero()
    }

    @Test
    fun `updating a product changes the name as sent and the price and keeps the brand`() {
        prepareProduct()
        entityManager.flushAndClear()

        productRegister.update(product.id, createProductAdminUpdateRequest(name = " 후드티 ", price = 25_000))
        entityManager.flushAndClear()
        val found = productFinder.findInfo(product.id)

        assertThat(found.name).isEqualTo(" 후드티 ")
        assertThat(found.price).isEqualTo(25_000L)
        assertThat(found.brandId).isEqualTo(brand.id)
    }

    @Test
    fun `updating the stock sets the final quantity, including zero`() {
        prepareProduct()
        entityManager.flushAndClear()

        productRegister.updateStock(product.id, createProductAdminStockUpdateRequest(quantity = 0))
        entityManager.flushAndClear()
        val found = productFinder.findInfo(product.id)

        assertThat(found.stock).isZero()
        assertThat(found.soldOut).isTrue()
    }

    @Test
    fun `deleting a product makes it a product that does not exist`() {
        prepareProduct()
        entityManager.flushAndClear()

        productRegister.delete(product.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { productFinder.find(product.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(countProducts()).isZero()
        assertThat(deletedAtOf(product.id)).isNotNull()
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
        prepareProduct()
        deleteProduct()
        entityManager.flushAndClear()

        assertThat(errorTypeOf { productRegister.update(product.id, createProductAdminUpdateRequest()) })
            .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(errorTypeOf { productRegister.updateStock(product.id, createProductAdminStockUpdateRequest()) })
            .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(errorTypeOf { productRegister.delete(product.id) }).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    /** 브랜드 삭제가 함께 삭제한 상품도 직접 삭제한 상품처럼 없는 상품이다(ADR 0017). */
    @Test
    fun `updating, setting the stock of, and deleting a product deleted with its brand all throw PRODUCT_NOT_FOUND`() {
        prepareProduct()
        deleteBrand()
        entityManager.flushAndClear()

        assertThat(errorTypeOf { productRegister.update(product.id, createProductAdminUpdateRequest()) })
            .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(errorTypeOf { productRegister.updateStock(product.id, createProductAdminStockUpdateRequest()) })
            .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        assertThat(errorTypeOf { productRegister.delete(product.id) }).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    @Test
    fun `an update rejected by request validation keeps the stored name and price`() {
        prepareProduct(name = "티셔츠", price = 12_000)
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.update(product.id, createProductAdminUpdateRequest(price = 0))
        }
        entityManager.flushAndClear()
        val found = productFinder.findInfo(product.id)

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 가격은 1원 이상이어야 합니다.")
        assertThat(found.name).isEqualTo("티셔츠")
        assertThat(found.price).isEqualTo(12_000L)
    }

    @Test
    fun `a negative stock is rejected by request validation and keeps the stored stock`() {
        prepareProduct(stock = 7)
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            productRegister.updateStock(product.id, createProductAdminStockUpdateRequest(quantity = -1))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("재고는 0 이상이어야 합니다.")
        assertThat(productFinder.findInfo(product.id).stock).isEqualTo(7)
    }

    /** 논리 삭제는 행을 지우지 않으므로 삭제 시각은 SQL 제한을 지나는 native 조회로만 볼 수 있다. */
    private fun deletedAtOf(id: Long): Any? {
        return entityManager
            .createNativeQuery("select deleted_at from product where id = :id")
            .setParameter("id", id)
            .singleResult
    }

    /** 삭제되지 않은 상품 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countProducts(): Long {
        return entityManager
            .createQuery("select count(p) from Product p", Long::class.java)
            .singleResult
    }
}
