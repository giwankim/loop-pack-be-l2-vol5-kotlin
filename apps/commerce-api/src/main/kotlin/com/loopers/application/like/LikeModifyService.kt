package com.loopers.application.like

import com.loopers.application.like.provided.Liker
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.user.provided.UserFinder
import com.loopers.domain.like.Like
import com.loopers.support.stereotype.ApplicationService

/**
 * [Liker]의 구현. 관계 자체에는 규칙이 없고 유스케이스가 순서를 정한다(도메인 문서 좋아요).
 *
 * 요청자가 있는지는 [UserFinder]에 묻는다. 헤더가 없는 것은 adapter.webapi가 401로 거절하지만,
 * Controller를 거치지 않는 호출도 같은 검사를 받게 하려고 여기서 다시 본다(설계 5.25, 5.27).
 */
@ApplicationService
class LikeModifyService(
    private val likeRepository: LikeRepository,
    private val productFinder: ProductFinder,
    private val userFinder: UserFinder,
) : Liker {
    /**
     * 삭제되지 않은 상품에 요청자의 관계를 만든다. 이미 있으면 그대로 두고 성공으로 답한다(설계 5.6).
     * 상품이 없거나 삭제됐으면 [ProductFinder.find]가 `PRODUCT_NOT_FOUND`로 거절한다.
     */
    override fun like(userId: Long, productId: Long) {
        userFinder.checkExists(userId)
        productFinder.find(productId)
        if (likeRepository.existsByUserIdAndProductId(userId, productId)) return

        likeRepository.save(Like(userId = userId, productId = productId))
    }

    /**
     * 요청자의 관계를 없앤다. 없어도 성공으로 답한다(설계 5.6). 행을 지우며 논리 삭제를 쓰지 않는다(ADR 0001).
     * 상품의 존재는 보지 않는다. 삭제된 상품에 남은 좋아요도 취소되어야 하기 때문이다.
     */
    override fun unlike(userId: Long, productId: Long) {
        userFinder.checkExists(userId)
        likeRepository.findByUserIdAndProductId(userId, productId)?.let { likeRepository.delete(it) }
    }
}
