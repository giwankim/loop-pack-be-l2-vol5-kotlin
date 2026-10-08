package com.loopers.application.product.provided

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.errorTypeOf
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import com.loopers.support.withStatistics
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [ProductFinder]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [ProductRegisterTest]와 같다.
 */
class ProductFinderTest(
    private val productFinder: ProductFinder,
) : BaseApplicationServiceTest() {
    /** 좋아요 수는 관계에서 센다. */
    @Test
    fun `finding a product counts the likes on it`() {
        prepareBrand()
        val registered = prepareProduct(brand)
        val other = prepareProduct(brand)
        val firstUser = prepareUser()
        val secondUser = prepareUser()
        prepareLike(firstUser, registered)
        prepareLike(secondUser, registered)
        prepareLike(firstUser, other)
        entityManager.flushAndClear()

        val found = productFinder.find(registered.id)

        assertThat(found.likeCount).isEqualTo(2L)
    }

    @Test
    fun `listing carries each product's own like count and zero for a product without likes`() {
        prepareBrand()
        val liked = prepareProduct(brand)
        val unliked = prepareProduct(brand)
        prepareLike(product = liked)
        prepareLike(product = liked)
        entityManager.flushAndClear()

        val slice = productFinder.findAll(ProductListRequest())

        assertThat(slice.content.map { it.id to it.likeCount }).containsExactly(unliked.id to 0L, liked.id to 2L)
    }

    /**
     * 목록의 좋아요 수는 조각의 식별자 목록에 대해 한 번에 센다(설계 5.28). 항목마다 세면 조각 크기만큼 SQL이 늘어난다.
     * 조각 조회 하나와 집계 하나, 둘이어야 한다.
     */
    @Test
    fun `listing counts the likes of the whole slice in one query`() {
        prepareBrand()
        val products = List(3) { prepareProduct(brand) }
        prepareUser()
        products.forEach { prepareLike(user, it) }
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val slice = productFinder.findAll(ProductListRequest())

            assertThat(slice.content).hasSize(3)
            assertThat(slice.content.map { it.likeCount }).containsOnly(1L)
            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        }
    }

    @Test
    fun `finding a product with zero stock reports it as sold out`() {
        prepareProduct(stock = 0)
        entityManager.flushAndClear()

        val found = productFinder.find(product.id)

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
        prepareProduct(name = "티셔츠", price = 12_000)
        entityManager.flushAndClear()

        val found = productFinder.findOrderable(product.id)

        assertThat(found?.id).isEqualTo(product.id)
        assertThat(found?.name).isEqualTo("티셔츠")
        assertThat(found?.price?.amount).isEqualTo(12_000L)
    }

    /**
     * 주문은 없음을 오류가 아니라 정상 결과로 받아 자기 오류로 옮긴다. 그래서 세 경우 모두 던지지 않고 null이다.
     * 삭제된 브랜드의 상품은 상품 행이 남아 있어도 주문할 수 없다.
     */
    @Test
    fun `the orderable lookup gives null for an unknown or deleted product and for a product of a deleted brand`() {
        val deleted = prepareProduct()
        deleteProduct(deleted)
        val closedBrand = prepareBrand()
        val ofClosedBrand = prepareProduct(closedBrand)
        deleteBrandKeepingProducts(closedBrand)
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
        prepareBrand(name = "루퍼스")
        prepareProduct(brand)
        prepareProduct(brand)
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
        prepareBrand(name = "루퍼스")
        val first = prepareProduct(brand, price = 3_000)
        val second = prepareProduct(brand, price = 30_000)
        entityManager.flushAndClear()

        val slice = productFinder.findAll(ProductListRequest())

        assertThat(slice.number).isEqualTo(ProductListRequest.DEFAULT_PAGE)
        assertThat(slice.size).isEqualTo(ProductListRequest.DEFAULT_SIZE)
        assertThat(slice.content.map { it.id }).containsExactly(second.id, first.id)
        assertThat(slice.content.map { it.brandName }).containsOnly("루퍼스")
    }

    @Test
    fun `listing a customer sort reaches the slice order`() {
        prepareBrand()
        val cheap = prepareProduct(brand, price = 3_000)
        val dear = prepareProduct(brand, price = 30_000)
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
        prepareBrand()
        val liked = prepareProduct(brand)
        val unliked = prepareProduct(brand)
        val mostLiked = prepareProduct(brand)
        val firstUser = prepareUser()
        val secondUser = prepareUser()
        prepareLike(firstUser, liked)
        prepareLike(firstUser, mostLiked)
        prepareLike(secondUser, mostLiked)
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
        prepareBrand()
        val products = List(3) { prepareProduct(brand) }
        prepareUser()
        products.forEach { prepareLike(user, it) }
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val slice = productFinder.findAll(ProductListRequest(sort = "likes_desc"))

            assertThat(slice.content).hasSize(3)
            assertThat(slice.content.map { it.likeCount }).containsOnly(1L)
            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
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
}
