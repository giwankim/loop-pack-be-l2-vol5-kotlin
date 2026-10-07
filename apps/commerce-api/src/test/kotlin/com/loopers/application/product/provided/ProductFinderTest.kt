package com.loopers.application.product.provided

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.like.required.LikeRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.like.Like
import com.loopers.domain.product.createProductAdminRegisterRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.statistics
import com.loopers.support.stereotype.ApplicationServiceTest
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [ProductFinder]를 실제 MySQL 위에서 확인한다. 읽을 상품은 같은 조각의 [ProductRegister]로 만든다.
 * 정리와 flush/clear, 브랜드를 저장 약속으로 만드는 까닭은 [ProductRegisterTest]와 같다. 좋아요도 다른 조각의 준비물이라 저장 약속으로 만든다.
 */
@ApplicationServiceTest
class ProductFinderTest(
    private val productFinder: ProductFinder,
    private val productRegister: ProductRegister,
    private val brandRepository: BrandRepository,
    private val likeRepository: LikeRepository,
    private val entityManager: EntityManager,
) {
    /** 좋아요 수는 관계에서 센다. 사용자 행은 필요 없다. 좋아요는 사용자를 식별자로만 가리킨다(설계 2). */
    @Test
    fun `finding a product counts the likes on it`() {
        val brand = brandRepository.save(createBrand())
        val registered = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        val other = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        likeRepository.save(Like(userId = 1L, productId = registered.id))
        likeRepository.save(Like(userId = 2L, productId = registered.id))
        likeRepository.save(Like(userId = 1L, productId = other.id))
        entityManager.flushAndClear()

        val found = productFinder.find(registered.id)

        assertThat(found.likeCount).isEqualTo(2L)
    }

    @Test
    fun `listing carries each product's own like count and zero for a product without likes`() {
        val brand = brandRepository.save(createBrand())
        val liked = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        val unliked = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        likeRepository.save(Like(userId = 1L, productId = liked.id))
        likeRepository.save(Like(userId = 2L, productId = liked.id))
        entityManager.flushAndClear()

        val slice = productFinder.findAll(ProductListRequest())

        assertThat(slice.content.map { it.id to it.likeCount }).containsExactly(unliked.id to 0L, liked.id to 2L)
    }

    /**
     * 목록의 좋아요 수는 조각의 식별자 목록에 대해 한 번에 센다(설계 5.28). 항목마다 세면 조각 크기만큼 SQL이 늘어난다.
     * 조각 조회 하나와 집계 하나, 둘이어야 한다. 통계는 컨텍스트를 새로 띄우지 않으려고 실행 중에 켠다.
     */
    @Test
    fun `listing counts the likes of the whole slice in one query`() {
        val brand = brandRepository.save(createBrand())
        val products = List(3) { productRegister.register(createProductAdminRegisterRequest(brandId = brand.id)) }
        products.forEach { likeRepository.save(Like(userId = 1L, productId = it.id)) }
        entityManager.flushAndClear()
        val statistics = entityManager.statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        try {
            val slice = productFinder.findAll(ProductListRequest())

            assertThat(slice.content).hasSize(3)
            assertThat(slice.content.map { it.likeCount }).containsOnly(1L)
            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        } finally {
            statistics.isStatisticsEnabled = false
        }
    }

    @Test
    fun `finding a product with zero stock reports it as sold out`() {
        val brand = brandRepository.save(createBrand())
        val registered = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, stock = 0))
        entityManager.flushAndClear()

        val found = productFinder.find(registered.id)

        assertThat(found.stock).isZero()
        assertThat(found.soldOut).isTrue()
    }

    @Test
    fun `getting an unknown product throws PRODUCT_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { productFinder.find(999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
    }

    @Test
    fun `the orderable lookup gives a product of a brand that is not deleted`() {
        val brand = brandRepository.save(createBrand())
        val request = createProductAdminRegisterRequest(brandId = brand.id)
        val registered = productRegister.register(request)
        entityManager.flushAndClear()

        val found = productFinder.findOrderable(registered.id)

        assertThat(found?.id).isEqualTo(registered.id)
        assertThat(found?.name).isEqualTo(request.name)
        assertThat(found?.price?.amount).isEqualTo(request.price)
    }

    /**
     * 주문은 없음을 오류가 아니라 정상 결과로 받아 자기 오류로 옮긴다. 그래서 세 경우 모두 던지지 않고 null이다.
     * 삭제된 브랜드의 상품은 상품 행이 남아 있어도 주문할 수 없다.
     */
    @Test
    fun `the orderable lookup gives null for an unknown or deleted product and for a product of a deleted brand`() {
        val brand = brandRepository.save(createBrand())
        val deleted = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        productRegister.delete(deleted.id)
        val closedBrand = brandRepository.save(createBrand())
        val ofClosedBrand = productRegister.register(createProductAdminRegisterRequest(brandId = closedBrand.id))
        closedBrand.delete()
        entityManager.flushAndClear()

        assertThat(productFinder.findOrderable(Long.MAX_VALUE)).isNull()
        assertThat(productFinder.findOrderable(deleted.id)).isNull()
        assertThat(productFinder.findOrderable(ofClosedBrand.id)).isNull()
    }

    /**
     * 삭제 필터·브랜드 필터·`hasNext`는 저장소 테스트가 지키므로 여기서 되풀이하지 않는다(설계 6).
     * 이 자리가 보는 것은 입력의 기본값이 조각에 닿는지와, 항목이 트랜잭션 안에서 브랜드 이름까지 채워지는지다.
     */
    @Test
    fun `listing carries the default page and size into the slice and fills the brand name`() {
        val brand = brandRepository.save(createBrand(name = "루퍼스"))
        productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        entityManager.flushAndClear()

        val slice = productFinder.findAll(ProductAdminListRequest())

        assertThat(slice.number).isEqualTo(ProductListRequest.DEFAULT_PAGE)
        assertThat(slice.size).isEqualTo(ProductListRequest.DEFAULT_SIZE)
        assertThat(slice.content.map { it.brandName }).containsOnly("루퍼스")
    }

    /**
     * 고객 목록도 아무것도 고르지 않으면 늦게 등록된 상품이 앞선다. 정렬 자체는 저장소 테스트가 지킨다.
     *
     * 먼저 등록한 상품을 싸게 두는 까닭은 기본값이 `latest`가 아니라 `price_asc`로 바뀌면 차례가 뒤집히게
     * 하려는 것이다. 값이 같으면 두 기준이 같은 차례를 내놓아 기본값이 무엇이든 이 테스트가 지나간다.
     */
    @Test
    fun `listing for a customer carries the default page, size, and sort into the slice`() {
        val brand = brandRepository.save(createBrand(name = "루퍼스"))
        val first = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 3_000))
        val second = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 30_000))
        entityManager.flushAndClear()

        val slice = productFinder.findAll(ProductListRequest())

        assertThat(slice.number).isEqualTo(ProductListRequest.DEFAULT_PAGE)
        assertThat(slice.size).isEqualTo(ProductListRequest.DEFAULT_SIZE)
        assertThat(slice.content.map { it.id }).containsExactly(second.id, first.id)
        assertThat(slice.content.map { it.brandName }).containsOnly("루퍼스")
    }

    @Test
    fun `listing a customer sort reaches the slice order`() {
        val brand = brandRepository.save(createBrand())
        val cheap = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 3_000))
        val dear = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id, price = 30_000))
        entityManager.flushAndClear()

        val slice = productFinder.findAll(ProductListRequest(sort = "price_asc"))

        assertThat(slice.content.map { it.id }).containsExactly(cheap.id, dear.id)
    }

    /**
     * 좋아요 많은순은 차례를 내는 쿼리와 `likeCount`를 세는 쿼리가 서로 다르다(설계 5.32). 둘이 어긋나면
     * 차례는 맞는데 수가 남의 것이 된다. 그래서 이 자리만은 차례와 값을 함께 본다.
     */
    @Test
    fun `listing by likes orders the slice and carries each product's own count`() {
        val brand = brandRepository.save(createBrand())
        val liked = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        val unliked = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        val mostLiked = productRegister.register(createProductAdminRegisterRequest(brandId = brand.id))
        likeRepository.save(Like(userId = 1L, productId = liked.id))
        likeRepository.save(Like(userId = 1L, productId = mostLiked.id))
        likeRepository.save(Like(userId = 2L, productId = mostLiked.id))
        entityManager.flushAndClear()

        val slice = productFinder.findAll(ProductListRequest(sort = "likes_desc"))

        assertThat(slice.content.map { it.id to it.likeCount })
            .containsExactly(mostLiked.id to 2L, liked.id to 1L, unliked.id to 0L)
    }

    /**
     * 좋아요 많은순도 조각 조회 하나와 집계 하나, 둘이어야 한다. 기본 정렬을 세는 위쪽 테스트와 겹쳐 보이지만
     * 겹치지 않는다. 이 기준만 `group by`가 붙어(설계 5.32) 총 개수를 세고 싶은 유혹이 생기는 자리이고,
     * 조각을 뜨는 방법이 기준마다 갈리므로 세는 자리도 기준마다 있어야 한다(설계 5.5).
     */
    @Test
    fun `listing by likes counts the likes of the whole slice in one query`() {
        val brand = brandRepository.save(createBrand())
        val products = List(3) { productRegister.register(createProductAdminRegisterRequest(brandId = brand.id)) }
        products.forEach { likeRepository.save(Like(userId = 1L, productId = it.id)) }
        entityManager.flushAndClear()
        val statistics = entityManager.statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        try {
            val slice = productFinder.findAll(ProductListRequest(sort = "likes_desc"))

            assertThat(slice.content).hasSize(3)
            assertThat(slice.content.map { it.likeCount }).containsOnly(1L)
            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        } finally {
            statistics.isStatisticsEnabled = false
        }
    }

    /**
     * 모르는 정렬 값은 Controller를 거치지 않고 불러도 거절된다. 배치나 컨슈머가 요청을 손으로 만들어
     * 부르는 자리가 여기이므로, 철자를 거르는 일이 HTTP 밖에도 있어야 한다.
     */
    @Test
    fun `a sort no product sort answers to is rejected without any controller`() {
        assertThat(errorTypeOf { productFinder.findAll(ProductListRequest(sort = "likes")) })
            .isEqualTo(ErrorType.INVALID_SORT)
        assertThat(errorTypeOf { productFinder.findAll(ProductListRequest(sort = "LATEST")) })
            .isEqualTo(ErrorType.INVALID_SORT)
        assertThat(errorTypeOf { productFinder.findAll(ProductListRequest(sort = "")) })
            .isEqualTo(ErrorType.INVALID_SORT)
    }

    @Test
    fun `listing for a customer outside the page and size bounds is rejected by request validation`() {
        assertThat(
            assertThrows<ConstraintViolationException> { productFinder.findAll(ProductListRequest(page = -1)) }
                .constraintViolations.map { it.message },
        ).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(
            assertThrows<ConstraintViolationException> { productFinder.findAll(ProductListRequest(size = 101)) }
                .constraintViolations.map { it.message },
        ).containsExactly("size는 100 이하여야 합니다.")
    }

    @Test
    fun `listing outside the page and size bounds is rejected by request validation`() {
        assertThat(
            assertThrows<ConstraintViolationException> { productFinder.findAll(ProductAdminListRequest(page = -1)) }
                .constraintViolations.map { it.message },
        ).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(
            assertThrows<ConstraintViolationException> { productFinder.findAll(ProductAdminListRequest(size = 0)) }
                .constraintViolations.map { it.message },
        ).containsExactly("size는 1 이상이어야 합니다.")
        assertThat(
            assertThrows<ConstraintViolationException> { productFinder.findAll(ProductAdminListRequest(size = 101)) }
                .constraintViolations.map { it.message },
        ).containsExactly("size는 100 이하여야 합니다.")
    }

    /** 거절에 실린 [ErrorType]. 모르는 철자 셋이 모두 같은 거절을 받으므로 한 자리에 모은다. */
    private fun errorTypeOf(call: () -> Unit): ErrorType =
        assertThrows<CoreException> { call() }.errorType
}
