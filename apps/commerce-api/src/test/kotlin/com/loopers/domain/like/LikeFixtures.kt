package com.loopers.domain.like

import com.loopers.application.like.provided.LikeRequest
import org.instancio.kotlin.KInstancio
import org.instancio.kotlin.KSelect.field

/**
 * 좋아요 누르기 요청. 상품 ID는 다른 애그리거트라 테스트가 넘긴다. 뽑는 값이 없어 계약 테스트를 두지 않는다.
 * `Like` 엔티티는 다른 애그리거트의 ID만 받아 fixture 없이 생성자를 부른다.
 */
fun createLikeRequest(productId: Long): LikeRequest {
    return KInstancio.of<LikeRequest>()
        .set(field(LikeRequest::productId), productId)
        .create()
}
