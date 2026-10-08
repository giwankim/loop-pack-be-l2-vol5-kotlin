package com.loopers.application.like.provided

/**
 * 좋아요를 바꾸기 전에 보는 사전 조건. 관계 자체에는 규칙이 없으므로(도메인 문서 좋아요) 여기 있는 것은
 * 다른 조각에 물어야 답할 수 있는 조건이다(ADR 0014). 첫 거절에서 던지고 멈춘다.
 */
interface LikeValidator {
    /**
     * 좋아요를 누를 수 있는 상품인지 본다. 상품이 없거나 삭제됐으면 `PRODUCT_NOT_FOUND`를 던진다.
     * 취소에는 사전 조건이 없어 짝이 되는 검사도 없다. 삭제된 상품에 남은 좋아요도 취소되어야 하므로 상품을 보지 않는다(ADR 0001).
     */
    fun validateForLike(productId: Long)
}
