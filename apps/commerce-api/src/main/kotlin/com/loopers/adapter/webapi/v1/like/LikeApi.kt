package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.RequesterId
import com.loopers.application.like.provided.Liker
import com.loopers.support.stereotype.WebApiAdapter
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * 좋아요는 상품 아래의 자원이라 경로가 상품을 품는다. 요청자는 웹 경계가 받아들여 넘긴다([RequesterId]).
 * 두 요청 모두 최종 상태를 말하는 것이라(설계 5.6) 응답에 data가 없다(설계 4).
 */
@WebApiAdapter
@RequestMapping("/api/v1/products/{productId}/likes")
class LikeApi(
    private val liker: Liker,
) : LikeApiSpec {
    @PostMapping
    override fun like(
        @RequesterId userId: Long,
        @PathVariable("productId") productId: Long,
    ): ApiResponse<Any> {
        liker.like(userId = userId, productId = productId)
        return ApiResponse.success()
    }

    @DeleteMapping
    override fun unlike(
        @RequesterId userId: Long,
        @PathVariable("productId") productId: Long,
    ): ApiResponse<Any> {
        liker.unlike(userId = userId, productId = productId)
        return ApiResponse.success()
    }
}
