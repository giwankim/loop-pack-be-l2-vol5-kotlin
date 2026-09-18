package com.loopers.infrastructure.like

import com.loopers.domain.like.Like
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * [Like]의 Spring Data JPA 저장소. [LikeRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다.
 * 좋아요는 논리 삭제가 없으므로 삭제 필터도 없다(ADR 0001).
 */
interface LikeJpaRepository : JpaRepository<Like, Long> {
    fun existsByUserIdAndProductId(userId: Long, productId: Long): Boolean

    fun findByUserIdAndProductId(userId: Long, productId: Long): Like?

    fun countByProductId(productId: Long): Long

    /** 상품마다 한 행. 좋아요가 없는 상품은 행이 없으므로 [LikeRepositoryImpl]이 0을 채운다. */
    @Query(
        "select l.productId as productId, count(l) as likeCount " +
            "from Like l where l.productId in :productIds group by l.productId",
    )
    fun countByProductIdIn(productIds: Collection<Long>): List<ProductLikeCount>
}

/** 그룹 집계 한 행. Spring Data가 인터페이스 프로젝션으로 채운다. */
interface ProductLikeCount {
    val productId: Long
    val likeCount: Long
}
