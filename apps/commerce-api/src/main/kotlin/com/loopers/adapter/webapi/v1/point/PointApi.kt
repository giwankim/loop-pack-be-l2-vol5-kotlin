package com.loopers.adapter.webapi.v1.point

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.RequesterId
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.point.provided.PointChargeRequest
import com.loopers.application.point.provided.PointCharger
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

/**
 * 고객 포인트. 요청자는 웹 경계가 받아들여 넘긴다([RequesterId]). 충전은 요청마다 새 충전이다(ADR 0005).
 * 본문은 다른 Controller처럼 application Request로 바로 받는다(카탈로그 설계 5.17).
 */
@WebApiAdapter
@RequestMapping("/api/v1/points")
class PointApi(
    private val pointAccountFinder: PointAccountFinder,
    private val pointCharger: PointCharger,
) : PointApiSpec {
    @PostMapping("/charge")
    override fun charge(
        @RequesterId userId: Long,
        @RequestBody @Valid request: PointChargeRequest,
    ): ApiResponse<PointAccountResponse> {
        return pointCharger.charge(userId, request)
            .let { PointAccountResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping
    override fun getBalance(
        @RequesterId userId: Long,
    ): ApiResponse<PointAccountResponse> {
        return pointAccountFinder.findBalance(userId)
            .let { PointAccountResponse.from(it) }
            .let { ApiResponse.success(it) }
    }
}
