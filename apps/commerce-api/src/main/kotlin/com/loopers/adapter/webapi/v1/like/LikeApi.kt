package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.adapter.webapi.RequesterId
import com.loopers.adapter.webapi.v1.product.ProductResponse
import com.loopers.application.like.provided.LikeFinder
import com.loopers.application.like.provided.LikeListRequest
import com.loopers.application.like.provided.LikeRequest
import com.loopers.application.like.provided.Liker
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

/**
 * 고객 좋아요. 요청자의 좋아요는 주문·포인트처럼 최상위 경로에 두고, 요청자는 웹 경계가 받아들여 넘긴다([RequesterId]).
 * 경로가 사용자를 품지 않으므로 남의 좋아요를 가리킬 길이 없다(설계 5.36).
 *
 * 누르기는 컬렉션에 더하는 것이라 상품을 본문으로 받고, 취소는 좋아요 하나를 가리키는 경로로 받는다.
 * 두 요청 모두 최종 상태를 말하는 것이라(설계 5.6) 관계를 처음 만들 때도 200이고 응답에 data가 없다(설계 4, 5.36).
 *
 * 목록의 항목은 고객 상품 목록의 항목과 같아야 하므로 같은 [ProductResponse]를 쓴다. 필드를 다시 적으면 두 목록이
 * 말없이 어긋날 수 있다. 조각의 봉투도 다른 목록과 같은 [PageResponse]다(설계 5.5).
 */
@WebApiAdapter
@RequestMapping("/api/v1/likes")
class LikeApi(
    private val liker: Liker,
    private val likeFinder: LikeFinder,
) : LikeApiSpec {
    /** 본문은 다른 Controller처럼 application Request로 바로 받는다(설계 5.17). */
    @PostMapping
    override fun like(
        @RequesterId userId: Long,
        @RequestBody @Valid request: LikeRequest,
    ): ApiResponse<Any> {
        liker.like(userId = userId, request = request)

        return ApiResponse.success()
    }

    @DeleteMapping("/{productId}")
    override fun unlike(
        @RequesterId userId: Long,
        @PathVariable("productId") productId: Long,
    ): ApiResponse<Any> {
        liker.unlike(userId = userId, productId = productId)

        return ApiResponse.success()
    }

    /** 쿼리 문자열을 [LikeListRequest]로 바로 받는 까닭은 다른 목록과 같다(설계 5.17, 5.22). */
    @GetMapping
    override fun getLikedProducts(
        @RequesterId userId: Long,
        @ModelAttribute @Valid request: LikeListRequest,
    ): ApiResponse<PageResponse<ProductResponse>> {
        val likedProducts = likeFinder.findLikedProducts(userId = userId, request = request)

        return ApiResponse.success(PageResponse.from(likedProducts.map(ProductResponse::from)))
    }
}
