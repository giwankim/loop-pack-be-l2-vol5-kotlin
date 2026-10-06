package com.loopers.application.product.required

/**
 * 상품을 옮길 때 좋아요 조각에 묻는 것. 좋아요 수는 관계를 세어 구하므로 답은 좋아요 쪽에만 있다(CONTEXT.md 좋아요 수).
 *
 * 묻는 쪽인 상품이 모양을 선언하고 좋아요 조각의 Finder가 이것을 상속해 구현한다. 그래서 상품은 좋아요를 참조하지 않고,
 * 의존은 도메인에서처럼 좋아요에서 상품으로만 간다.
 */
interface LikeCounter {
    /** [productId] 상품의 좋아요 수. 좋아요가 없으면 0이다. */
    fun countLikes(productId: Long): Long

    /**
     * [productIds]마다의 좋아요 수. 좋아요가 없는 상품도 0으로 들어 있어 부르는 쪽이 빠진 키를 다루지 않는다.
     * 목록이 항목마다 세지 않고 한 번에 세게 하려는 것이다(설계 5.28).
     */
    fun countLikes(productIds: Collection<Long>): Map<Long, Long>
}
