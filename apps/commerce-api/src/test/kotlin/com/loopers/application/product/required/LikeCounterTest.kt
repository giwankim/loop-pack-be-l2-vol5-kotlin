package com.loopers.application.product.required

import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import com.loopers.support.withStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 상품이 선언한 [LikeCounter]를 좋아요 조각의 구현으로 실제 MySQL 위에서 확인한다. 테스트는 포트가 선언된 패키지에 둔다
 * ([com.loopers.application.user.required.UserRepositoryTest]와 같다). 정리와 flush/clear의 까닭은
 * [com.loopers.application.like.provided.LikerTest]와 같다.
 */
class LikeCounterTest(
    private val likeCounter: LikeCounter,
) : BaseApplicationServiceTest() {
    /**
     * 요청한 상품마다 값이 있어 부르는 쪽이 빠진 키를 다루지 않는다. 묻지 않은 상품의 좋아요는 세지 않는다(설계 5.28).
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

        val counts = likeCounter.countLikes(listOf(likedTwice.id, likedOnce.id, unliked.id))

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
            val counts = likeCounter.countLikes(emptyList())

            assertThat(counts).isEmpty()
            assertThat(statistics.prepareStatementCount).isZero()
        }
    }
}
