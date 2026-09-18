package com.loopers.infrastructure.like

import com.loopers.domain.like.Like
import com.loopers.domain.like.LikeRepository
import org.springframework.stereotype.Component

/**
 * [LikeRepository]의 구현. 일은 모두 [LikeJpaRepository]에 맡기고, 상품 여러 개의 집계만 domain이 약속한 모양으로 옮긴다.
 * 두 인터페이스를 하나로 합치지 않는 이유는 [com.loopers.infrastructure.brand.BrandRepositoryImpl]과 같다(설계 5.20).
 */
@Component
class LikeRepositoryImpl(
    private val likeJpaRepository: LikeJpaRepository,
) : LikeRepository {
    override fun save(like: Like): Like = likeJpaRepository.save(like)

    override fun existsByUserIdAndProductId(userId: Long, productId: Long): Boolean =
        likeJpaRepository.existsByUserIdAndProductId(userId, productId)

    override fun findByUserIdAndProductId(userId: Long, productId: Long): Like? =
        likeJpaRepository.findByUserIdAndProductId(userId, productId)

    override fun delete(like: Like) = likeJpaRepository.delete(like)

    override fun countByProductId(productId: Long): Long = likeJpaRepository.countByProductId(productId)

    /**
     * 그룹 집계는 좋아요가 있는 상품만 돌려주므로 요청한 식별자마다 0을 기본으로 채운다.
     * 빈 목록은 SQL을 보내지 않는다. `in ()`은 MySQL이 거절한다.
     */
    override fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> {
        if (productIds.isEmpty()) return emptyMap()
        val counted = likeJpaRepository.countByProductIdIn(productIds).associate { it.productId to it.likeCount }
        return productIds.associateWith { counted[it] ?: 0L }
    }
}
