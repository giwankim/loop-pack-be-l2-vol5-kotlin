package com.loopers.application.product.provided

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * 한 사용자가 좋아요를 누른 상품 목록의 입력. 사용자 식별자는 요청자에서 오므로 여기 없고
 * [ProductFinder.findAllLikedBy]의 파라미터다.
 *
 * 좋아요 조각이 자기 [com.loopers.application.like.provided.LikeListRequest]를 이것으로 옮겨 부른다. 상품은 좋아요를
 * 모르므로 좋아요의 Request를 받지 않는다. 범위는 상품의 목록이라 [ProductListRequest]의 상수를 읽는다. 좋아요 쪽의 상한이
 * 이보다 넓어지면 넘는 크기는 여기서 거절되므로, 두 상한은 함께 본다. 제약이 붙는 자리와 까닭은 [ProductListRequest]와
 * 같다(설계 5.18, 5.22).
 */
data class ProductLikedListRequest(
    @Min(0, message = "page는 0 이상이어야 합니다.")
    val page: Int = ProductListRequest.DEFAULT_PAGE,
    @Min(1, message = "size는 1 이상이어야 합니다.")
    @Max(ProductListRequest.MAX_SIZE.toLong(), message = "size는 {value} 이하여야 합니다.")
    val size: Int = ProductListRequest.DEFAULT_SIZE,
)
