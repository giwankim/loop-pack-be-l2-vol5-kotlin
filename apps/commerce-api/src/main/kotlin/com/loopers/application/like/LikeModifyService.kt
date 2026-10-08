package com.loopers.application.like

import com.loopers.application.like.provided.Liker
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.like.Like
import com.loopers.support.stereotype.ApplicationService

/**
 * [Liker]의 구현. 관계 자체에는 규칙이 없고 유스케이스가 순서를 정한다(도메인 문서 좋아요).
 * 요청자는 웹 경계가 이미 받아들였으므로 받은 `userId`를 그대로 믿는다(ADR 0015).
 */
@ApplicationService
class LikeModifyService(
    private val likeRepository: LikeRepository,
    private val productFinder: ProductFinder,
) : Liker {
    /**
     * 삭제되지 않은 상품에 요청자의 관계를 만든다. 이미 있으면 그대로 두고 성공으로 답한다(설계 5.6).
     * 상품이 없거나 삭제됐으면 [ProductFinder.find]가 `PRODUCT_NOT_FOUND`로 거절한다.
     */
    override fun like(userId: Long, productId: Long) {
        productFinder.find(productId)
        if (likeRepository.existsByUserIdAndProductId(userId, productId)) return

        likeRepository.save(Like(userId = userId, productId = productId))
    }

    /**
     * 요청자의 관계를 없앤다. 없어도 성공으로 답한다(설계 5.6). 행을 지우며 논리 삭제를 쓰지 않는다(ADR 0001).
     * 상품의 존재는 보지 않는다. 삭제된 상품에 남은 좋아요도 취소되어야 하기 때문이다.
     */
    override fun unlike(userId: Long, productId: Long) {
        likeRepository.findByUserIdAndProductId(userId, productId)?.let { likeRepository.delete(it) }
    }
}
