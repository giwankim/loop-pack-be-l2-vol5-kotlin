package com.loopers.application.like

import com.loopers.application.like.provided.LikeRequest
import com.loopers.application.like.provided.LikeValidator
import com.loopers.application.like.provided.Liker
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.product.provided.ProductListChangeMarker
import com.loopers.domain.like.Like
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [Liker]의 구현. 누르기가 Request를 받으므로 검증하는 Service다. 관계 자체에는 규칙이 없고 유스케이스가 순서를 정한다(도메인 문서 좋아요).
 * 다른 조각에 물어야 하는 사전 조건은 [LikeValidator]가 보고, 여기에는 단계의 차례만 있다(ADR 0014).
 * 요청자는 웹 경계가 이미 받아들였으므로 받은 `userId`를 그대로 믿는다(ADR 0015).
 * 좋아요 수는 관계에서 세므로 누르기와 취소는 고객 목록을 바꾼다. 둘 다 [ProductListChangeMarker]로 표시해 커밋 바로 앞에
 * 상품 목록 revision을 올린다. 그래서 좋아요는 상품을 잠그지 않지만 커밋에서 revision 행을 잠근다(ADR 0020).
 */
@ValidatedApplicationService
class LikeModifyService(
    private val likeRepository: LikeRepository,
    private val likeValidator: LikeValidator,
    private val productListChangeMarker: ProductListChangeMarker,
) : Liker {
    /**
     * 삭제되지 않은 상품에 요청자의 관계를 만든다. 이미 있으면 그대로 두고 성공으로 답한다(설계 5.6).
     * 상품이 없거나 삭제됐으면 [LikeValidator.validateForLike]가 `PRODUCT_NOT_FOUND`로 거절한다.
     * 이미 있어 그대로 둘 때도 표시한다. 바뀌지 않은 누르기를 가려내는 분기는 값어치가 없다(ADR 0020 규칙 1).
     */
    override fun like(userId: Long, request: LikeRequest) {
        likeValidator.validateForLike(request.productId)
        productListChangeMarker.markChanged()
        if (likeRepository.existsByUserIdAndProductId(userId, request.productId)) {
            return
        }

        likeRepository.save(Like(userId = userId, productId = request.productId))
    }

    /**
     * 요청자의 관계를 없앤다. 없어도 성공으로 답한다(설계 5.6). 행을 지우며 논리 삭제를 쓰지 않는다(ADR 0001).
     * 상품의 존재는 보지 않는다. 삭제된 상품에 남은 좋아요도 취소되어야 하기 때문이다.
     * 관계가 없어 지울 것이 없어도 표시한다(ADR 0020 규칙 1). 좋아요 행은 잠그지 않고 읽으므로 DELETE가 flush에서 처음 그 행을 잠근다.
     * 표시의 훅이 올리기 전에 flush하므로 그 DELETE가 revision보다 앞선다(ADR 0020 규칙 3).
     */
    override fun unlike(userId: Long, productId: Long) {
        productListChangeMarker.markChanged()
        likeRepository.findByUserIdAndProductId(userId, productId)?.let { likeRepository.delete(it) }
    }
}
