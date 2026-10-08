package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.adapter.webapi.RequesterId
import com.loopers.adapter.webapi.v1.product.ProductResponse
import com.loopers.application.like.provided.LikeFinder
import com.loopers.application.like.provided.LikeListRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping

/**
 * 내 좋아요 목록. 경로가 상품이 아니라 사용자를 품으므로 [LikeApi]와 root가 갈려 컨트롤러도 따로 둔다.
 * 고객과 관리자가 root로 갈리는 것과 같은 모양이다.
 *
 * 항목은 고객 상품 목록의 항목과 같아야 하므로 같은 [ProductResponse]를 쓴다. 필드를 다시 적으면 두 목록이
 * 말없이 어긋날 수 있다. 조각의 봉투도 다른 목록과 같은 [PageResponse]다(설계 5.5).
 */
@WebApiAdapter
@RequestMapping("/api/v1/users/{userId}/likes")
class UserLikeApi(
    private val likeFinder: LikeFinder,
) : UserLikeApiSpec {
    /**
     * 경로의 사용자가 요청자 자신인지 본다. 다르면 403이다. 요청자는 자기 좋아요만 다룰 수 있다(CONTEXT.md 요청자).
     * 경로와 요청자는 둘 다 HTTP가 실어 준 값이라 그 비교도 여기서 하고, application으로는 요청자만 넘어간다(설계 5.30).
     * 없는 요청자는 [RequesterId]가 이미 401로 거절했다. 인증이 인가보다 앞선다(ADR 0015).
     *
     * 쿼리 문자열을 [LikeListRequest]로 바로 받는 까닭은 다른 목록과 같다(설계 5.17, 5.22).
     */
    @GetMapping
    override fun getLikedProducts(
        @RequesterId userId: Long,
        @PathVariable("userId") pathUserId: Long,
        @ModelAttribute @Valid request: LikeListRequest,
    ): ApiResponse<PageResponse<ProductResponse>> {
        if (userId != pathUserId) {
            throw CoreException(ErrorType.FORBIDDEN)
        }

        val likedProducts = likeFinder.findLikedProducts(userId = userId, request = request)

        return ApiResponse.success(PageResponse.from(likedProducts.map(ProductResponse::from)))
    }
}
