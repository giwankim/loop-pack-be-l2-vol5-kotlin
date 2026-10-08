package com.loopers.application.like.provided

import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.product.required.LikeCounter
import jakarta.validation.Valid
import org.springframework.data.domain.Slice

/**
 * 좋아요 조각이 내주는 읽기. 고객의 내 좋아요 목록이 부른다.
 *
 * 상품이 선언한 [LikeCounter]에도 답한다. 좋아요가 상품의 물음을 따르므로 상품은 좋아요를 모른다.
 */
interface LikeFinder : LikeCounter {
    /**
     * 요청자가 좋아요를 누른, 삭제되지 않은 상품 한 조각. 최근에 누른 상품이 앞선다.
     * 요청자는 웹 경계가 이미 받아들였으므로 다시 확인하지 않는다(ADR 0015).
     *
     * 받는 사용자 식별자는 요청자 하나뿐이라 남의 목록을 내줄 길이 없다(설계 5.30). 경로도 사용자를 품지 않는다(설계 5.36).
     */
    fun findLikedProducts(userId: Long, @Valid request: LikeListRequest): Slice<ProductInfo>
}
