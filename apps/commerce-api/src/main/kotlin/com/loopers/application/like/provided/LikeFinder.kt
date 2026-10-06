package com.loopers.application.like.provided

import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.product.required.LikeCounter
import com.loopers.domain.shared.PageSlice
import jakarta.validation.Valid

/**
 * 좋아요 조각이 내주는 읽기. 고객의 내 좋아요 목록이 부른다.
 *
 * 상품이 선언한 [LikeCounter]에도 답한다. 좋아요가 상품의 물음을 따르므로 상품은 좋아요를 모른다.
 */
interface LikeFinder : LikeCounter {
    /**
     * 요청자가 좋아요를 누른, 삭제되지 않은 상품 한 조각. 최근에 누른 상품이 앞선다.
     * 요청자가 없으면 `UNAUTHORIZED`를 던진다(설계 5.27).
     *
     * 받는 사용자 식별자는 요청자 하나뿐이라 남의 목록을 내줄 길이 없다. 경로가 가리키는 사용자와 요청자가
     * 같은지는 둘 다 HTTP가 실은 값이라 adapter.webapi가 본다(설계 5.30).
     */
    fun findLikedProducts(userId: Long, @Valid request: LikeListRequest): PageSlice<ProductInfo>
}
