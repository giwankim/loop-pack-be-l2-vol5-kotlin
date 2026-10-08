package com.loopers.application.like.provided

import com.loopers.support.countLikes
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import com.loopers.support.withStatistics
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [LikeFinder]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear, 관계를 `likes` 테이블에서 세는 까닭은 [LikerTest]와 같다.
 */
class LikeFinderTest(
    private val likeFinder: LikeFinder,
) : BaseApplicationServiceTest() {
    /**
     * 요청자 구분. 포트가 받는 사용자 식별자는 요청자 하나뿐이라 남의 목록을 내줄 길이 없고,
     * 같은 상품을 둘이 눌러도 각자의 목록에는 자기 관계만 오른다.
     */
    @Test
    fun `the like list gives only the user's own likes`() {
        val me = prepareUser()
        prepareBrand()
        val mine = prepareProduct(brand)
        val theirs = prepareProduct(brand)
        prepareLike(me, mine)
        prepareLike(product = theirs)
        entityManager.flushAndClear()

        val slice = likeFinder.findLikedProducts(me.id, LikeListRequest())

        assertThat(slice.content.map { it.id }).containsExactly(mine.id)
    }

    /** 삭제된 상품은 없는 상품이라 목록에서 빠진다. 좋아요 행은 남아 있어 취소할 수 있다(ADR 0001). */
    @Test
    fun `the like list leaves out a product that was deleted after it was liked`() {
        prepareUser()
        prepareBrand()
        val active = prepareProduct(brand)
        val deleted = prepareProduct(brand)
        prepareLike(user, active)
        prepareLike(user, deleted)
        deleteProduct(deleted)
        entityManager.flushAndClear()

        val slice = likeFinder.findLikedProducts(user.id, LikeListRequest())

        assertThat(slice.content.map { it.id }).containsExactly(active.id)
        assertThat(entityManager.countLikes(user.id, deleted.id)).isOne()
    }

    /**
     * 조각의 차례와 `hasNext`는 [com.loopers.application.product.required.ProductRepositoryTest]가 SQL로 이미 고정한다.
     * 여기서는 입력이 조각까지 이어지는지와, 트랜잭션 안에서만 읽을 수 있는 값이 항목에 실리는지를 본다(설계 6).
     * 좋아요 수는 상품에 걸린 관계의 개수이므로 요청자의 것만 세지 않는다.
     */
    @Test
    fun `the like list carries the default page and size into the slice and fills the brand name and like count`() {
        val me = prepareUser()
        prepareBrand(name = "루퍼스")
        prepareProduct(brand, name = "티셔츠")
        prepareLike(me, product)
        prepareLike(product = product)
        entityManager.flushAndClear()

        val slice = likeFinder.findLikedProducts(me.id, LikeListRequest())

        assertThat(slice.number).isEqualTo(LikeListRequest.DEFAULT_PAGE)
        assertThat(slice.size).isEqualTo(LikeListRequest.DEFAULT_SIZE)
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.content.single().brandName).isEqualTo("루퍼스")
        assertThat(slice.content.single().name).isEqualTo("티셔츠")
        assertThat(slice.content.single().likeCount).isEqualTo(2L)
    }

    /**
     * 조각에 몇 개가 담기든 조회는 둘이다. 상품과 브랜드를 함께 읽는 조각 하나, 좋아요 수 집계 하나.
     * 항목마다 브랜드를 읽거나 좋아요를 세면 조각 크기만큼 늘어난다(설계 5.28, 5.29). 요청자는 웹 경계가 확인하므로 세지 않는다(ADR 0015).
     */
    @Test
    fun `the like list reads a slice of any size in two queries`() {
        prepareUser()
        val products = List(3) { prepareProduct() }
        products.forEach { prepareLike(user, it) }
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val slice = likeFinder.findLikedProducts(user.id, LikeListRequest())

            assertThat(slice.content).hasSize(3)
            assertThat(slice.content.map { it.brandName })
                .containsExactlyInAnyOrderElementsOf(products.map { it.brand.name })
            assertThat(slice.content.map { it.likeCount }).containsOnly(1L)
            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        }
    }

    @Test
    fun `listing likes outside the page and size bounds is rejected by request validation`() {
        prepareUser()
        entityManager.flushAndClear()

        assertThat(
            assertThrows<ConstraintViolationException> {
                likeFinder.findLikedProducts(user.id, LikeListRequest(page = -1))
            }.constraintViolations.map { it.message },
        ).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(
            assertThrows<ConstraintViolationException> {
                likeFinder.findLikedProducts(user.id, LikeListRequest(size = 0))
            }.constraintViolations.map { it.message },
        ).containsExactly("size는 1 이상이어야 합니다.")
        assertThat(
            assertThrows<ConstraintViolationException> {
                likeFinder.findLikedProducts(user.id, LikeListRequest(size = 101))
            }.constraintViolations.map { it.message },
        ).containsExactly("size는 100 이하여야 합니다.")
    }

    /**
     * 상품이 선언한 [com.loopers.application.product.required.LikeCounter]의 약속. 요청한 상품마다 값이 있어
     * 부르는 쪽이 빠진 키를 다루지 않는다. 묻지 않은 상품의 좋아요는 세지 않는다(설계 5.28).
     */
    @Test
    fun `counting likes for several products gives each its own count and zero for a product without likes`() {
        val firstUser = prepareUser()
        val secondUser = prepareUser()
        val likedTwice = prepareProduct()
        val likedOnce = prepareProduct()
        val unliked = prepareProduct()
        val notAsked = prepareProduct()
        prepareLike(firstUser, likedTwice)
        prepareLike(secondUser, likedTwice)
        prepareLike(firstUser, likedOnce)
        prepareLike(firstUser, notAsked)
        entityManager.flushAndClear()

        val counts = likeFinder.countLikes(listOf(likedTwice.id, likedOnce.id, unliked.id))

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(mapOf(likedTwice.id to 2L, likedOnce.id to 1L, unliked.id to 0L))
    }

    /**
     * 빈 조각의 좋아요 수는 SQL 없이 비어 있다. 빈 목록을 그대로 보내도 Hibernate가 `in`을 `1=0`으로 바꿔 같은 빈 답이
     * 오므로, 결과만 보아서는 거르는 일이 사라져도 모른다. 그래서 나가지 않은 SQL을 센다.
     */
    @Test
    fun `counting likes for no products is empty and sends no SQL`() {
        prepareLike()
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val counts = likeFinder.countLikes(emptyList())

            assertThat(counts).isEmpty()
            assertThat(statistics.prepareStatementCount).isZero()
        }
    }
}
