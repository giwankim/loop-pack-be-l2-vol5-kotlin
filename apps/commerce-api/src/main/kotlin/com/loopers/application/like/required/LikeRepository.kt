package com.loopers.application.like.required

import com.loopers.domain.like.Like
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

/**
 * 좋아요 저장소. Spring Data가 구현을 만든다. 취소가 행을 지우므로(ADR 0001) 삭제 필터가 없고, 있는 행이 곧 관계다.
 */
interface LikeRepository : Repository<Like, Long> {
    fun save(like: Like): Like

    fun existsByUserIdAndProductId(userId: Long, productId: Long): Boolean

    fun findByUserIdAndProductId(userId: Long, productId: Long): Like?

    /** 관계 행을 지운다. 좋아요는 `BaseEntity.delete()`의 논리 삭제를 쓰지 않는다(ADR 0001). */
    fun delete(like: Like)

    /** 한 상품의 좋아요 수. 관계를 세어 구하며 상품에 저장하지 않는다(CONTEXT.md 좋아요 수). */
    fun countByProductId(productId: Long): Long

    /**
     * [productIds]마다의 좋아요 수. 좋아요가 없는 상품도 0으로 들어 있어 부르는 쪽이 빠진 키를 다루지 않는다.
     * 목록이 항목마다 세지 않고 한 번에 세게 하려는 것이다(설계 5.28).
     *
     * 그룹 집계는 좋아요가 있는 상품만 돌려주므로 요청한 식별자마다 0을 기본으로 채운다.
     * 빈 목록은 SQL을 보내지 않는다. `in ()`은 MySQL이 거절한다.
     *
     * 본문이 있는 메서드는 JVM default method가 되고, Spring Data는 그것을 쿼리로 만들지 않고 본문을 실행한다.
     */
    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> {
        if (productIds.isEmpty()) return emptyMap()
        val counted = findProductLikeCounts(productIds).associate { it.productId to it.likeCount }
        return productIds.associateWith { counted[it] ?: 0L }
    }

    /**
     * [countByProductIds]가 맡기는 집계. 상품마다 한 행이고 좋아요가 없는 상품은 행이 없다.
     * 포트가 빠진 키를 내주지 않도록 바깥에서는 [countByProductIds]를 부른다.
     */
    @Query(
        "select l.productId as productId, count(l) as likeCount " +
            "from Like l where l.productId in :productIds group by l.productId",
    )
    fun findProductLikeCounts(productIds: Collection<Long>): List<ProductLikeCount>
}

/** 그룹 집계 한 행. Spring Data가 인터페이스 프로젝션으로 채운다. */
interface ProductLikeCount {
    val productId: Long
    val likeCount: Long
}
