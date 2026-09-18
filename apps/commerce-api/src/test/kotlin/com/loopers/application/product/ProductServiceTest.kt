package com.loopers.application.product

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.like.Like
import com.loopers.domain.like.LikeRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import com.loopers.utils.statistics
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
    private val likeRepository: LikeRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `registering under an active brand saves a product that can be fetched back`() {
        val brand = brandRepository.save(Brand("루퍼스"))

        val registered =
            productService.register(ProductAdminRegisterRequest(brandId = brand.id, name = " 티셔츠 ", price = 12_000, stock = 7))
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
            { assertThat(found.likeCount).isZero() },
            { assertThat(found.createdAt).isNotNull() },
            { assertThat(found.updatedAt).isNotNull() },
        )
    }

    /** 좋아요 수는 관계에서 센다. 사용자 행은 필요 없다. 좋아요는 사용자를 식별자로만 가리킨다(설계 2). */
    @Test
    fun `finding a product counts the likes on it`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registered = register(brand.id)
        val other = register(brand.id, name = "후드티")
        likeRepository.save(Like(userId = 1L, productId = registered.id))
        likeRepository.save(Like(userId = 2L, productId = registered.id))
        likeRepository.save(Like(userId = 1L, productId = other.id))
        entityManager.flushAndClear()

        val found = productService.find(registered.id)

        assertThat(found.likeCount).isEqualTo(2L)
    }

    @Test
    fun `listing carries each product's own like count and zero for a product without likes`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val liked = register(brand.id, name = "티셔츠")
        val unliked = register(brand.id, name = "후드티")
        likeRepository.save(Like(userId = 1L, productId = liked.id))
        likeRepository.save(Like(userId = 2L, productId = liked.id))
        entityManager.flushAndClear()

        val slice = productService.findAll(ProductListRequest())

        assertThat(slice.items.map { it.id to it.likeCount }).containsExactly(unliked.id to 0L, liked.id to 2L)
    }

    /**
     * 목록의 좋아요 수는 조각의 식별자 목록에 대해 한 번에 센다(설계 5.28). 항목마다 세면 조각 크기만큼 SQL이 늘어난다.
     * 조각 조회 하나와 집계 하나, 둘이어야 한다. 통계는 컨텍스트를 새로 띄우지 않으려고 실행 중에 켠다.
     */
    @Test
    fun `listing counts the likes of the whole slice in one query`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val products = listOf("티셔츠", "후드티", "양말").map { register(brand.id, name = it) }
        products.forEach { likeRepository.save(Like(userId = 1L, productId = it.id)) }
        entityManager.flushAndClear()
        val statistics = entityManager.statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        try {
            val slice = productService.findAll(ProductListRequest())

            assertAll(
                { assertThat(slice.items).hasSize(3) },
                { assertThat(slice.items.map { it.likeCount }).containsOnly(1L) },
                { assertThat(statistics.prepareStatementCount).isEqualTo(2L) },
            )
        } finally {
            statistics.isStatisticsEnabled = false
        }
    }

    @Test
    fun `finding a product with zero stock reports it as sold out`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registered =
            productService.register(ProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000, stock = 0))
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
            productService.register(ProductAdminRegisterRequest(brandId = 999L, name = "티셔츠", price = 12_000, stock = 7))
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
            productService.register(ProductAdminRegisterRequest(brandId = deleted.id, name = "티셔츠", price = 12_000, stock = 7))
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
            productService.register(ProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 0, stock = 7))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 가격은 1원 이상이어야 합니다.") },
            { assertThat(countProducts()).isZero() },
        )
    }

    @Test
    fun `registering a blank name is rejected by request validation before the domain and saves nothing`() {
        val brand = brandRepository.save(Brand("루퍼스"))

        val exception = assertThrows<ConstraintViolationException> {
            productService.register(ProductAdminRegisterRequest(brandId = brand.id, name = "   ", price = 12_000, stock = 7))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 이름은 공백일 수 없습니다.") },
            { assertThat(countProducts()).isZero() },
        )
    }

    @Test
    fun `registering a negative stock is rejected by request validation before the domain and saves nothing`() {
        val brand = brandRepository.save(Brand("루퍼스"))

        val exception = assertThrows<ConstraintViolationException> {
            productService.register(ProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000, stock = -1))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.constraintViolations.map { it.message }).containsExactly("재고는 0 이상이어야 합니다.") },
            { assertThat(countProducts()).isZero() },
        )
    }

    @Test
    fun `getting an unknown product throws PRODUCT_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { productService.find(999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    @Test
    fun `updating a product changes the name and price and keeps the brand`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registered = register(brand.id, name = "티셔츠", price = 12_000)
        entityManager.flushAndClear()

        productService.update(registered.id, ProductAdminUpdateRequest(name = " 후드티 ", price = 25_000))
        entityManager.flushAndClear()
        val found = productService.find(registered.id)

        assertAll(
            { assertThat(found.name).isEqualTo("후드티") },
            { assertThat(found.price).isEqualTo(25_000L) },
            { assertThat(found.brandId).isEqualTo(brand.id) },
        )
    }

    @Test
    fun `updating the stock sets the final quantity, including zero`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registered = register(brand.id, stock = 7)
        entityManager.flushAndClear()

        productService.updateStock(registered.id, ProductAdminStockUpdateRequest(quantity = 0))
        entityManager.flushAndClear()
        val found = productService.find(registered.id)

        assertAll(
            { assertThat(found.stock).isZero() },
            { assertThat(found.soldOut).isTrue() },
        )
    }

    @Test
    fun `deleting a product makes it a product that does not exist`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registered = register(brand.id)
        entityManager.flushAndClear()

        productService.delete(registered.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { productService.find(registered.id) }

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
            { assertThat(countProducts()).isZero() },
            { assertThat(deletedAtOf(registered.id)).isNotNull() },
        )
    }

    /**
     * 삭제 필터·브랜드 필터·`hasNext`는 저장소 테스트가 지키므로 여기서 되풀이하지 않는다(설계 6).
     * 이 자리가 보는 것은 입력의 기본값이 조각에 닿는지와, 항목이 트랜잭션 안에서 브랜드 이름까지 채워지는지다.
     */
    @Test
    fun `listing carries the default page and size into the slice and fills the brand name`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        register(brand.id, name = "티셔츠")
        register(brand.id, name = "후드티")
        entityManager.flushAndClear()

        val slice = productService.findAll(ProductAdminListRequest())

        assertAll(
            { assertThat(slice.page).isEqualTo(ProductListRequest.DEFAULT_PAGE) },
            { assertThat(slice.size).isEqualTo(ProductListRequest.DEFAULT_SIZE) },
            { assertThat(slice.items.map { it.brandName }).containsOnly("루퍼스") },
        )
    }

    /**
     * 고객 목록도 아무것도 고르지 않으면 늦게 등록된 상품이 앞선다. 정렬 자체는 저장소 테스트가 지킨다.
     *
     * 먼저 등록한 상품을 싸게 두는 까닭은 기본값이 `latest`가 아니라 `price_asc`로 바뀌면 차례가 뒤집히게
     * 하려는 것이다. 값이 같으면 두 기준이 같은 차례를 내놓아 기본값이 무엇이든 이 테스트가 지나간다.
     */
    @Test
    fun `listing for a customer carries the default page, size, and sort into the slice`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val first = register(brand.id, name = "티셔츠", price = 3_000)
        val second = register(brand.id, name = "후드티", price = 30_000)
        entityManager.flushAndClear()

        val slice = productService.findAll(ProductListRequest())

        assertAll(
            { assertThat(slice.page).isEqualTo(ProductListRequest.DEFAULT_PAGE) },
            { assertThat(slice.size).isEqualTo(ProductListRequest.DEFAULT_SIZE) },
            { assertThat(slice.items.map { it.id }).containsExactly(second.id, first.id) },
            { assertThat(slice.items.map { it.brandName }).containsOnly("루퍼스") },
        )
    }

    @Test
    fun `listing a customer sort reaches the slice order`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val cheap = register(brand.id, name = "양말", price = 3_000)
        val dear = register(brand.id, name = "코트", price = 30_000)
        entityManager.flushAndClear()

        val slice = productService.findAll(ProductListRequest(sort = "price_asc"))

        assertThat(slice.items.map { it.id }).containsExactly(cheap.id, dear.id)
    }

    /**
     * 모르는 정렬 값은 Controller를 거치지 않고 불러도 거절된다. 배치나 컨슈머가 요청을 손으로 만들어
     * 부르는 자리가 여기이므로, 철자를 거르는 일이 HTTP 밖에도 있어야 한다.
     */
    @Test
    fun `a sort no product sort answers to is rejected without any controller`() {
        assertAll(
            {
                assertThat(errorTypeOf { productService.findAll(ProductListRequest(sort = "likes_desc")) })
                    .isEqualTo(ErrorType.INVALID_SORT)
            },
            {
                assertThat(errorTypeOf { productService.findAll(ProductListRequest(sort = "LATEST")) })
                    .isEqualTo(ErrorType.INVALID_SORT)
            },
            {
                assertThat(errorTypeOf { productService.findAll(ProductListRequest(sort = "")) })
                    .isEqualTo(ErrorType.INVALID_SORT)
            },
        )
    }

    @Test
    fun `listing for a customer outside the page and size bounds is rejected by request validation`() {
        assertAll(
            {
                assertThat(
                    assertThrows<ConstraintViolationException> { productService.findAll(ProductListRequest(page = -1)) }
                        .constraintViolations.map { it.message },
                ).containsExactly("page는 0 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> { productService.findAll(ProductListRequest(size = 101)) }
                        .constraintViolations.map { it.message },
                ).containsExactly("size는 100 이하여야 합니다.")
            },
        )
    }

    @Test
    fun `updating, setting the stock of, and deleting an unknown product all throw PRODUCT_NOT_FOUND`() {
        assertAll(
            {
                assertThat(errorTypeOf { productService.update(999L, ProductAdminUpdateRequest("후드티", 25_000)) })
                    .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
            },
            {
                assertThat(errorTypeOf { productService.updateStock(999L, ProductAdminStockUpdateRequest(3)) })
                    .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
            },
            { assertThat(errorTypeOf { productService.delete(999L) }).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
        )
    }

    @Test
    fun `updating, setting the stock of, and deleting a deleted product all throw PRODUCT_NOT_FOUND`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val deleted = register(brand.id)
        productService.delete(deleted.id)
        entityManager.flushAndClear()

        assertAll(
            {
                assertThat(errorTypeOf { productService.update(deleted.id, ProductAdminUpdateRequest("후드티", 25_000)) })
                    .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
            },
            {
                assertThat(errorTypeOf { productService.updateStock(deleted.id, ProductAdminStockUpdateRequest(3)) })
                    .isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
            },
            { assertThat(errorTypeOf { productService.delete(deleted.id) }).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
        )
    }

    @Test
    fun `an update rejected by request validation keeps the stored name and price`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registered = register(brand.id, name = "티셔츠", price = 12_000)
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            productService.update(registered.id, ProductAdminUpdateRequest(name = "후드티", price = 0))
        }
        entityManager.flushAndClear()
        val found = productService.find(registered.id)

        assertAll(
            { assertThat(exception.constraintViolations.map { it.message }).containsExactly("상품 가격은 1원 이상이어야 합니다.") },
            { assertThat(found.name).isEqualTo("티셔츠") },
            { assertThat(found.price).isEqualTo(12_000L) },
        )
    }

    @Test
    fun `a negative stock is rejected by request validation and keeps the stored stock`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registered = register(brand.id, stock = 7)
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            productService.updateStock(registered.id, ProductAdminStockUpdateRequest(quantity = -1))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.constraintViolations.map { it.message }).containsExactly("재고는 0 이상이어야 합니다.") },
            { assertThat(productService.find(registered.id).stock).isEqualTo(7) },
        )
    }

    @Test
    fun `listing outside the page and size bounds is rejected by request validation`() {
        assertAll(
            {
                assertThat(
                    assertThrows<ConstraintViolationException> { productService.findAll(ProductAdminListRequest(page = -1)) }
                        .constraintViolations.map { it.message },
                ).containsExactly("page는 0 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> { productService.findAll(ProductAdminListRequest(size = 0)) }
                        .constraintViolations.map { it.message },
                ).containsExactly("size는 1 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> { productService.findAll(ProductAdminListRequest(size = 101)) }
                        .constraintViolations.map { it.message },
                ).containsExactly("size는 100 이하여야 합니다.")
            },
        )
    }

    private fun register(brandId: Long, name: String = "티셔츠", price: Long = 12_000, stock: Int = 7): ProductInfo =
        productService.register(ProductAdminRegisterRequest(brandId = brandId, name = name, price = price, stock = stock))

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
