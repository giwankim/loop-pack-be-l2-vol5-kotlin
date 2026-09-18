package com.loopers.domain.like

/**
 * 좋아요 저장 약속. 취소가 행을 지우므로(ADR 0001) 삭제 필터가 없고, 있는 행이 곧 관계다.
 */
interface LikeRepository {
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
     */
    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long>
}
