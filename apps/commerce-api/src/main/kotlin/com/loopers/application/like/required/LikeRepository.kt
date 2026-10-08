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
     * [productIds] 상품들의 좋아요 수를 한 번에 센다. 좋아요가 있는 상품마다 한 행이고, 좋아요가 없는 상품은 행이 없다.
     * 목록이 항목마다 세지 않고 한 번에 세게 하려는 것이다(설계 5.28).
     *
     * 요청한 상품마다 값이 있다는 약속은 [com.loopers.application.product.required.LikeCounter]의 것이라,
     * 빠진 상품을 0으로 채우고 빈 목록을 거르는 일은 그 구현이 한다. 빈 목록으로 불러도 Hibernate가 조건을 `1=0`으로 바꿔
     * 빈 결과를 주지만, 조회는 한 번 나간다.
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
