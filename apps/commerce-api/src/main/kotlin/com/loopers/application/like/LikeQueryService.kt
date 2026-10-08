package com.loopers.application.like

import com.loopers.application.like.provided.LikeFinder
import com.loopers.application.like.provided.LikeListRequest
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.product.provided.ProductLikedListRequest
import com.loopers.support.stereotype.ValidatedApplicationService
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Slice

/**
 * [LikeFinder]의 구현. 목록이 Request를 받으므로 검증하는 Service다. 좋아요를 누른 상품은 [ProductFinder]로 읽는다.
 * 상품을 [ProductInfo]로 옮기는 규칙은 상품 조각에 하나뿐이라 여기서는 누구의 좋아요인지만 정한다(설계 5.31).
 *
 * [ProductFinder]는 `@Lazy`로 받아 처음 부를 때 찾는다. 상품 조각이 [ProductInfo]를 만들며 이 Service가 답하는
 * [com.loopers.application.product.required.LikeCounter]를 부르므로, 코드의 의존은 좋아요에서 상품으로만 가도
 * 빈은 좋아요 → 상품 → 좋아요로 서로를 기다린다. 생성자로 곧장 받으면 컨텍스트가 뜨지 않는다.
 */
@ValidatedApplicationService(readOnly = true)
class LikeQueryService(
    private val likeRepository: LikeRepository,
    @Lazy private val productFinder: ProductFinder,
) : LikeFinder {
    override fun findLikedProducts(userId: Long, request: LikeListRequest): Slice<ProductInfo> =
        productFinder.findAllLikedBy(userId, ProductLikedListRequest(page = request.page, size = request.size))

    override fun countLikes(productId: Long): Long = likeRepository.countByProductId(productId)

    /**
     * 그룹 집계는 좋아요가 있는 상품만 돌려주므로 요청한 식별자마다 0을 기본으로 채운다. 요청한 상품마다 값이 있다는 것은
     * 저장소가 아니라 상품이 선언한 [com.loopers.application.product.required.LikeCounter]의 약속이다.
     * 빈 목록은 SQL을 보내지 않는다. Hibernate가 빈 `in`을 `1=0`으로 바꿔 보내 답은 같지만, 빈 조각마다 헛된 왕복이 한 번 나간다.
     */
    override fun countLikes(productIds: Collection<Long>): Map<Long, Long> {
        if (productIds.isEmpty()) {
            return emptyMap()
        }

        val counted = likeRepository.findProductLikeCounts(productIds).associate { it.productId to it.likeCount }
        return productIds.associateWith { counted[it] ?: 0L }
    }
}
