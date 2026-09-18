package com.loopers.infrastructure.like

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.like.Like
import com.loopers.domain.like.LikeRepository
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.utils.countLikes
import com.loopers.utils.flushAndClear
import com.loopers.utils.likeRowExists
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

/**
 * [LikeRepositoryImpl]이 [LikeRepository] 계약을 실제 MySQL에서 지키는지 확인한다. 설정과 패키지 위치의 이유는
 * [com.loopers.infrastructure.brand.BrandRepositoryTest]와 같다.
 *
 * 좋아요는 사용자와 상품을 식별자로만 가리키므로(설계 2) 사용자·상품 행 없이 식별자만으로 만든다.
 * 유일 제약과 행 삭제는 DB가 지키는 약속이라 여기서 본다(ADR 0001).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig::class, MySqlTestContainersConfig::class, LikeRepositoryImpl::class)
class LikeRepositoryTest(
    private val likeRepository: LikeRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `findByUserIdAndProductId reads a saved like back after flush and clear`() {
        val saved = likeRepository.save(Like(userId = 1L, productId = 10L))
        entityManager.flushAndClear()

        val found = likeRepository.findByUserIdAndProductId(userId = 1L, productId = 10L)

        assertAll(
            { assertThat(found).isNotNull().isNotSameAs(saved) },
            { assertThat(found?.id).isEqualTo(saved.id) },
            { assertThat(found?.userId).isEqualTo(1L) },
            { assertThat(found?.productId).isEqualTo(10L) },
            { assertThat(found?.createdAt).isNotNull() },
        )
    }

    @Test
    fun `findByUserIdAndProductId is null when the pair has no like`() {
        likeRepository.save(Like(userId = 1L, productId = 10L))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(likeRepository.findByUserIdAndProductId(userId = 2L, productId = 10L)).isNull() },
            { assertThat(likeRepository.findByUserIdAndProductId(userId = 1L, productId = 11L)).isNull() },
        )
    }

    @Test
    fun `existsByUserIdAndProductId answers for the exact pair only`() {
        likeRepository.save(Like(userId = 1L, productId = 10L))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(likeRepository.existsByUserIdAndProductId(userId = 1L, productId = 10L)).isTrue() },
            { assertThat(likeRepository.existsByUserIdAndProductId(userId = 2L, productId = 10L)).isFalse() },
            { assertThat(likeRepository.existsByUserIdAndProductId(userId = 1L, productId = 11L)).isFalse() },
        )
    }

    /** 식별자가 IDENTITY라 저장이 곧 INSERT이므로, 같은 쌍의 두 번째 저장은 flush를 기다리지 않고 바로 거절된다. */
    @Test
    fun `saving a second like for the same user and product violates the unique constraint`() {
        likeRepository.save(Like(userId = 1L, productId = 10L))

        assertThrows<DataIntegrityViolationException> {
            likeRepository.save(Like(userId = 1L, productId = 10L))
        }
    }

    @Test
    fun `the same user may like different products and different users the same product`() {
        likeRepository.save(Like(userId = 1L, productId = 10L))
        likeRepository.save(Like(userId = 1L, productId = 11L))
        likeRepository.save(Like(userId = 2L, productId = 10L))
        entityManager.flushAndClear()

        assertThat(entityManager.countLikes()).isEqualTo(3L)
    }

    @Test
    fun `delete erases the row so the pair can no longer be found`() {
        val saved = likeRepository.save(Like(userId = 1L, productId = 10L))
        entityManager.flushAndClear()

        likeRepository.delete(likeRepository.findByUserIdAndProductId(userId = 1L, productId = 10L)!!)
        entityManager.flushAndClear()

        assertAll(
            { assertThat(likeRepository.findByUserIdAndProductId(userId = 1L, productId = 10L)).isNull() },
            { assertThat(entityManager.likeRowExists(saved.id)).isFalse() },
        )
    }

    /** 취소한 좋아요가 행으로 남으면 같은 쌍을 다시 누를 때 죽은 행과 충돌한다. 행을 지우는 까닭이다(ADR 0001). */
    @Test
    fun `a pair can be liked again after its like was deleted`() {
        val first = likeRepository.save(Like(userId = 1L, productId = 10L))
        likeRepository.delete(first)
        entityManager.flushAndClear()

        val second = likeRepository.save(Like(userId = 1L, productId = 10L))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(second.id).isNotEqualTo(first.id) },
            { assertThat(likeRepository.findByUserIdAndProductId(userId = 1L, productId = 10L)?.id).isEqualTo(second.id) },
            { assertThat(entityManager.countLikes()).isOne() },
        )
    }

    @Test
    fun `countByProductId counts the likes of one product`() {
        likeRepository.save(Like(userId = 1L, productId = 10L))
        likeRepository.save(Like(userId = 2L, productId = 10L))
        likeRepository.save(Like(userId = 1L, productId = 11L))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(likeRepository.countByProductId(10L)).isEqualTo(2L) },
            { assertThat(likeRepository.countByProductId(11L)).isEqualTo(1L) },
            { assertThat(likeRepository.countByProductId(12L)).isZero() },
        )
    }

    @Test
    fun `countByProductIds counts several products at once and reports zero for a product without likes`() {
        likeRepository.save(Like(userId = 1L, productId = 10L))
        likeRepository.save(Like(userId = 2L, productId = 10L))
        likeRepository.save(Like(userId = 1L, productId = 11L))
        likeRepository.save(Like(userId = 1L, productId = 13L))
        entityManager.flushAndClear()

        val counts = likeRepository.countByProductIds(listOf(10L, 11L, 12L))

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(mapOf(10L to 2L, 11L to 1L, 12L to 0L))
    }

    @Test
    fun `countByProductIds with no ids is empty`() {
        likeRepository.save(Like(userId = 1L, productId = 10L))
        entityManager.flushAndClear()

        assertThat(likeRepository.countByProductIds(emptyList())).isEmpty()
    }
}
