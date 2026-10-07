package com.loopers.application.like

import com.loopers.application.like.provided.LikeFinder
import com.loopers.application.like.provided.LikeListRequest
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductInfo
import com.loopers.application.user.provided.UserFinder
import com.loopers.support.stereotype.ValidatedApplicationService
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Slice
import org.springframework.transaction.annotation.Transactional

/**
 * [LikeFinder]의 구현. 목록이 Request를 받으므로 검증하는 Service다. 좋아요를 누른 상품은 [ProductFinder]로 읽는다.
 * 상품을 [ProductInfo]로 옮기는 규칙은 상품 조각에 하나뿐이라 여기서는 누구의 좋아요인지만 정한다(설계 5.31).
 * 읽기 메서드는 클래스의 `@Transactional`보다 앞서는 `@Transactional(readOnly = true)`를 단다.
 *
 * [ProductFinder]는 `@Lazy`로 받아 처음 부를 때 찾는다. 상품 조각이 [ProductInfo]를 만들며 이 Service가 답하는
 * [com.loopers.application.product.required.LikeCounter]를 부르므로, 코드의 의존은 좋아요에서 상품으로만 가도
 * 빈은 좋아요 → 상품 → 좋아요로 서로를 기다린다. 생성자로 곧장 받으면 컨텍스트가 뜨지 않는다.
 */
@ValidatedApplicationService
class LikeQueryService(
    private val likeRepository: LikeRepository,
    @Lazy private val productFinder: ProductFinder,
    private val userFinder: UserFinder,
) : LikeFinder {
    @Transactional(readOnly = true)
    override fun findLikedProducts(userId: Long, request: LikeListRequest): Slice<ProductInfo> {
        userFinder.checkExists(userId)
        return productFinder.findAllLikedBy(userId = userId, page = request.page, size = request.size)
    }

    @Transactional(readOnly = true)
    override fun countLikes(productId: Long): Long = likeRepository.countByProductId(productId)

    @Transactional(readOnly = true)
    override fun countLikes(productIds: Collection<Long>): Map<Long, Long> =
        likeRepository.countByProductIds(productIds)
}
