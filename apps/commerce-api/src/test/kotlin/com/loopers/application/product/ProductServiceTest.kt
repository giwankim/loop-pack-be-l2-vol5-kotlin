package com.loopers.application.product

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * [ProductService]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.brand.BrandServiceTest]와 같다.
 */
@SpringBootTest
@Transactional
class ProductServiceTest(
    private val productService: ProductService,
    private val brandRepository: BrandRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `registering under a live brand saves a product that can be fetched back`() {
        val brand = brandRepository.save(Brand("루퍼스"))

        val registered =
            productService.register(ProductRegisterRequest(brandId = brand.id, name = " 티셔츠 ", price = 12_000, stock = 7))
        entityManager.flushAndClear()
        val found = productService.find(registered.id)

        assertAll(
            { assertThat(registered.brandId).isEqualTo(brand.id) },
            { assertThat(registered.name).isEqualTo("티셔츠") },
            { assertThat(found.id).isEqualTo(registered.id) },
            { assertThat(found.brandId).isEqualTo(brand.id) },
            { assertThat(found.brandName).isEqualTo("루퍼스") },
            { assertThat(found.name).isEqualTo("티셔츠") },
            { assertThat(found.price).isEqualTo(12_000L) },
            { assertThat(found.stock).isEqualTo(7) },
            { assertThat(found.soldOut).isFalse() },
            { assertThat(found.createdAt).isNotNull() },
            { assertThat(found.updatedAt).isNotNull() },
        )
    }

    @Test
    fun `finding a product with zero stock reports it as sold out`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registered =
            productService.register(ProductRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000, stock = 0))
        entityManager.flushAndClear()

        val found = productService.find(registered.id)

        assertAll(
            { assertThat(found.stock).isZero() },
            { assertThat(found.soldOut).isTrue() },
        )
    }

    @Test
    fun `registering under an unknown brand throws BRAND_NOT_FOUND and saves nothing`() {
        val exception = assertThrows<CoreException> {
            productService.register(ProductRegisterRequest(brandId = 999L, name = "티셔츠", price = 12_000, stock = 7))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND) },
            { assertThat(countProducts()).isZero() },
        )
    }

    @Test
    fun `registering under a deleted brand throws BRAND_NOT_FOUND and saves nothing`() {
        val deleted = brandRepository.save(Brand("루퍼스").apply { delete() })
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> {
            productService.register(ProductRegisterRequest(brandId = deleted.id, name = "티셔츠", price = 12_000, stock = 7))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND) },
            { assertThat(countProducts()).isZero() },
        )
    }

    @Test
    fun `registering a price of zero is rejected by request validation before the domain and saves nothing`() {
        val brand = brandRepository.save(Brand("루퍼스"))

        val exception = assertThrows<ConstraintViolationException> {
            productService.register(ProductRegisterRequest(brandId = brand.id, name = "티셔츠", price = 0, stock = 7))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 가격은 1원 이상이어야 합니다.") },
            { assertThat(countProducts()).isZero() },
        )
    }

    @Test
    fun `getting an unknown product throws PRODUCT_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { productService.find(999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    /** 삭제되지 않은 상품 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countProducts(): Long =
        entityManager
            .createQuery("select count(p) from Product p", Long::class.java)
            .singleResult
}
