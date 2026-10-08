package com.loopers.application.like.provided

import jakarta.validation.Valid

/**
 * 고객의 좋아요 쓰기. 누르기·취소를 맡는다. 두 요청 모두 최종 상태를 말하므로 이미 그 상태여도 성공한다(설계 5.6).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface Liker {
    /** 없거나 삭제된 상품이면 `PRODUCT_NOT_FOUND`를 던진다. 이미 누른 상품이면 그대로 둔다. */
    fun like(userId: Long, @Valid request: LikeRequest)

    /** 좋아요가 없어도 그대로 둔다. 삭제된 상품에 남은 좋아요도 취소된다. */
    fun unlike(userId: Long, productId: Long)
}
