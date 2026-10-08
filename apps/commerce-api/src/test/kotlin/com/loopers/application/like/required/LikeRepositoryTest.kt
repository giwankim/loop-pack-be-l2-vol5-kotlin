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
 * 여러 상품의 그룹 집계 [LikeRepository.findProductLikeCounts]는 좋아요가 있는 상품만 행으로 준다. 요청한 상품마다 0을
 * 채우는 것은 [com.loopers.application.product.required.LikeCounter]의 약속이라
 * [com.loopers.application.like.provided.LikeFinderTest]가 본다.
 *
 * 유일 제약과 행 삭제는 DB가 지키는 약속이라 여기서 본다(ADR 0001). 사용자와 상품을 향한 외래 키도 그렇다. 좋아요는 둘을
 * 식별자로만 가리켜 JPA가 제약을 만들지 않으므로 `scalar-foreign-keys.sql`이 만들고, 만들어졌는지는 `information_schema`에서도
 * 확인한다(ADR 0015).
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

    /** 사용자 행이 없는 좋아요는 DB가 거절한다. application은 요청자를 믿으므로 이 외래 키가 지킨다(ADR 0015). */
    @Test
    fun `saving a like for a user that does not exist violates the foreign key`() {
        prepareProduct()

        assertThrows<DataIntegrityViolationException> {
            likeRepository.save(Like(userId = 999L, productId = product.id))
        }
    }

    @Test
    fun `saving a like for a product that does not exist violates the foreign key`() {
        prepareUser()

        assertThrows<DataIntegrityViolationException> {
            likeRepository.save(Like(userId = user.id, productId = 999L))
        }
    }

    @Test
    fun `the user and product foreign keys exist in the database`() {
        val foreignKeys = entityManager
            .createNativeQuery(
                "select constraint_name, referenced_table_name, referenced_column_name " +
                    "from information_schema.key_column_usage " +
                    "where table_schema = database() and table_name = 'likes' and referenced_table_name is not null",
            )
            .resultList
            .map { (it as Array<*>).joinToString(":") }

        assertThat(foreignKeys).containsExactlyInAnyOrder("FK_LIKES_USER:users:id", "FK_LIKES_PRODUCT:product:id")
    }

    /** 자연 식별자 컬럼에는 Hibernate가 제 이름으로 유일 키를 만들 수 있다. 쌍을 지키는 유일 인덱스는 이름 붙인 하나뿐이다(ADR 0016). */
    @Test
    fun `the pair has exactly one unique index and it is the named one`() {
        val uniqueIndexes = entityManager
            .createNativeQuery(
                "select index_name, group_concat(column_name order by seq_in_index) " +
                    "from information_schema.statistics where table_schema = database() and table_name = 'likes' " +
                    "and non_unique = 0 and index_name <> 'PRIMARY' group by index_name",
            )
            .resultList
            .map { (it as Array<*>).joinToString(":") }

        assertThat(uniqueIndexes).containsExactly("UK_LIKES_USER_ID_PRODUCT_ID:user_id,product_id")
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
    fun `findProductLikeCounts gives one row per liked product among the ids and none for a product without likes`() {
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

        val rows = likeRepository.findProductLikeCounts(listOf(likedTwice.id, likedOnce.id, unliked.id))

        assertThat(rows.map { it.productId to it.likeCount }).containsExactlyInAnyOrder(likedTwice.id to 2L, likedOnce.id to 1L)
    }
}
