package com.loopers.application.like.required

import com.loopers.domain.like.Like
import com.loopers.support.countLikes
import com.loopers.support.flushAndClear
import com.loopers.support.likeRowExists
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException

/**
 * Spring Data가 만든 [LikeRepository]가 실제 MySQL에서 계약을 지키는지 확인한다. 패키지 위치의 이유는
 * [com.loopers.application.user.required.UserRepositoryTest]와 같다.
 *
 * 여러 상품의 좋아요 수 [LikeRepository.countByProductIds]는 본문을 가진 인터페이스 메서드다. 그 테스트는 Spring Data가
 * 본문을 쿼리로 만들지 않고 실행해 좋아요가 없는 상품을 0으로 채운다는 것도 함께 고정한다.
 *
 * 유일 제약과 행 삭제는 DB가 지키는 약속이라 여기서 본다(ADR 0001).
 */
class LikeRepositoryTest(
    private val likeRepository: LikeRepository,
) : BaseRepositoryTest() {
    @Test
    fun `findByUserIdAndProductId reads a saved like back after flush and clear`() {
        prepareLike()
        entityManager.flushAndClear()

        val found = likeRepository.findByUserIdAndProductId(userId = user.id, productId = product.id)

        assertThat(found).isNotNull().isNotSameAs(like)
        assertThat(found?.id).isEqualTo(like.id)
        assertThat(found?.userId).isEqualTo(user.id)
        assertThat(found?.productId).isEqualTo(product.id)
        assertThat(found?.createdAt).isNotNull()
    }

    @Test
    fun `findByUserIdAndProductId is null when the pair has no like`() {
        val me = prepareUser()
        val other = prepareUser()
        val liked = prepareProduct()
        val unliked = prepareProduct()
        prepareLike(me, liked)
        entityManager.flushAndClear()

        assertThat(likeRepository.findByUserIdAndProductId(userId = other.id, productId = liked.id)).isNull()
        assertThat(likeRepository.findByUserIdAndProductId(userId = me.id, productId = unliked.id)).isNull()
    }

    @Test
    fun `existsByUserIdAndProductId answers for the exact pair only`() {
        val me = prepareUser()
        val other = prepareUser()
        val liked = prepareProduct()
        val unliked = prepareProduct()
        prepareLike(me, liked)
        entityManager.flushAndClear()

        assertThat(likeRepository.existsByUserIdAndProductId(userId = me.id, productId = liked.id)).isTrue()
        assertThat(likeRepository.existsByUserIdAndProductId(userId = other.id, productId = liked.id)).isFalse()
        assertThat(likeRepository.existsByUserIdAndProductId(userId = me.id, productId = unliked.id)).isFalse()
    }

    /** 식별자가 IDENTITY라 저장이 곧 INSERT이므로, 같은 쌍의 두 번째 저장은 flush를 기다리지 않고 바로 거절된다. */
    @Test
    fun `saving a second like for the same user and product violates the unique constraint`() {
        prepareLike()

        assertThrows<DataIntegrityViolationException> {
            likeRepository.save(Like(userId = user.id, productId = product.id))
        }
    }

    @Test
    fun `the same user may like different products and different users the same product`() {
        val me = prepareUser()
        val other = prepareUser()
        val first = prepareProduct()
        val second = prepareProduct()

        likeRepository.save(Like(userId = me.id, productId = first.id))
        likeRepository.save(Like(userId = me.id, productId = second.id))
        likeRepository.save(Like(userId = other.id, productId = first.id))
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes()).isEqualTo(3L)
    }

    @Test
    fun `delete erases the row so the pair can no longer be found`() {
        prepareLike()
        entityManager.flushAndClear()

        likeRepository.delete(likeRepository.findByUserIdAndProductId(userId = user.id, productId = product.id)!!)
        entityManager.flushAndClear()

        assertThat(likeRepository.findByUserIdAndProductId(userId = user.id, productId = product.id)).isNull()
        assertThat(entityManager.likeRowExists(like.id)).isFalse()
    }

    /** 취소한 좋아요가 행으로 남으면 같은 쌍을 다시 누를 때 죽은 행과 충돌한다. 행을 지우는 까닭이다(ADR 0001). */
    @Test
    fun `a pair can be liked again after its like was deleted`() {
        val first = prepareLike()
        unlike()
        entityManager.flushAndClear()

        val second = likeRepository.save(Like(userId = user.id, productId = product.id))
        entityManager.flushAndClear()

        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(likeRepository.findByUserIdAndProductId(userId = user.id, productId = product.id)?.id).isEqualTo(second.id)
        assertThat(entityManager.countLikes()).isOne()
    }

    @Test
    fun `countByProductId counts the likes of one product`() {
        val firstUser = prepareUser()
        val secondUser = prepareUser()
        val likedTwice = prepareProduct()
        val likedOnce = prepareProduct()
        val unliked = prepareProduct()
        prepareLike(firstUser, likedTwice)
        prepareLike(secondUser, likedTwice)
        prepareLike(firstUser, likedOnce)
        entityManager.flushAndClear()

        assertThat(likeRepository.countByProductId(likedTwice.id)).isEqualTo(2L)
        assertThat(likeRepository.countByProductId(likedOnce.id)).isEqualTo(1L)
        assertThat(likeRepository.countByProductId(unliked.id)).isZero()
    }

    @Test
    fun `countByProductIds counts several products at once and reports zero for a product without likes`() {
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

        val counts = likeRepository.countByProductIds(listOf(likedTwice.id, likedOnce.id, unliked.id))

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(mapOf(likedTwice.id to 2L, likedOnce.id to 1L, unliked.id to 0L))
    }

    @Test
    fun `countByProductIds with no ids is empty`() {
        prepareLike()
        entityManager.flushAndClear()

        assertThat(likeRepository.countByProductIds(emptyList())).isEmpty()
    }
}
