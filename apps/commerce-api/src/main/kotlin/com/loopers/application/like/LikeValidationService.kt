package com.loopers.application.like

import com.loopers.application.like.provided.LikeValidator
import com.loopers.application.product.provided.ProductFinder
import com.loopers.support.stereotype.ApplicationService

/**
 * [LikeValidator]의 구현. 상품이 있는지는 상품 조각의 [ProductFinder.find]에 묻는다. 그 조회는 상품만 읽고
 * 브랜드도 좋아요 수도 읽지 않는다(ADR 0014). 읽기만 하므로 `readOnly`로 열고, 누르기의 트랜잭션 안에서는 거기에 참여한다.
 */
@ApplicationService(readOnly = true)
class LikeValidationService(
    private val productFinder: ProductFinder,
) : LikeValidator {
    override fun validateForLike(productId: Long) {
        productFinder.find(productId)
    }
}
