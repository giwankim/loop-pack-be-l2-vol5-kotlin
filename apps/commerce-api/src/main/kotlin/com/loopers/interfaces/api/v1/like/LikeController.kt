package com.loopers.interfaces.api.v1.like

import com.loopers.application.like.LikeService
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.UserIdHeader
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 좋아요는 상품 아래의 자원이라 경로가 상품을 품는다. 요청자는 헤더에서 읽는다([UserIdHeader]).
 * 두 요청 모두 최종 상태를 말하는 것이라(설계 5.6) 응답에 data가 없다(설계 4).
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/likes")
class LikeController(
    private val likeService: LikeService,
) : LikeApiSpec {
    @PostMapping
    override fun like(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @PathVariable("productId") productId: Long,
    ): ApiResponse<Any> {
        likeService.like(userId = UserIdHeader.require(userId), productId = productId)
        return ApiResponse.success()
    }

    @DeleteMapping
    override fun unlike(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @PathVariable("productId") productId: Long,
    ): ApiResponse<Any> {
        likeService.unlike(userId = UserIdHeader.require(userId), productId = productId)
        return ApiResponse.success()
    }
}
