package com.loopers.application.like.provided

import jakarta.validation.constraints.Positive

/**
 * 좋아요 누르기 입력. 사용자 식별자는 요청자에서 오므로 여기 없고 [Liker.like]의 파라미터다(설계 5.27).
 * 취소의 상품 식별자는 경로로 오므로 Request 없이 [Liker.unlike]가 값으로 받는다(설계 5.36).
 */
data class LikeRequest(
    @Positive(message = "상품 ID는 1 이상이어야 합니다.")
    val productId: Long,
)
