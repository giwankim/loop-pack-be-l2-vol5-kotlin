package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.adapter.webapi.v1.product.ProductResponse
import com.loopers.application.like.provided.LikeFinder
import com.loopers.application.like.provided.LikeListRequest
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
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
     * 요청자와 경로의 사용자를 [UserIdHeader.requireSelf]가 견주어 본다. application으로는 요청자만 넘어간다.
     * 쿼리 문자열을 [LikeListRequest]로 바로 받는 까닭은 다른 목록과 같다(설계 5.17, 5.22).
     */
    @GetMapping
    override fun getLikedProducts(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @PathVariable("userId") pathUserId: Long,
        @ModelAttribute @Valid request: LikeListRequest,
    ): ApiResponse<PageResponse<ProductResponse>> {
        return likeFinder
            .findLikedProducts(userId = UserIdHeader.requireSelf(userId, pathUserId), request = request)
            .let { PageResponse.from(it, ProductResponse::from) }
            .let { ApiResponse.success(it) }
    }
}
