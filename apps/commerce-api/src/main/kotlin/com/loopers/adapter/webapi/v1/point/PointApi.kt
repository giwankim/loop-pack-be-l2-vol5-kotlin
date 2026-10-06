package com.loopers.adapter.webapi.v1.point

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.point.PointChargeRequest
import com.loopers.application.point.PointService
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping

/**
 * 고객 포인트. 요청자는 헤더에서 읽는다([UserIdHeader]). 충전은 요청마다 새 충전이다(ADR 0005).
 * 본문은 다른 Controller처럼 application Request로 바로 받는다(카탈로그 설계 5.17).
 */
@WebApiAdapter
@RequestMapping("/api/v1/points")
class PointApi(
    private val pointService: PointService,
) : PointApiSpec {
    @PostMapping("/charge")
    override fun charge(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @RequestBody @Valid request: PointChargeRequest,
    ): ApiResponse<PointAccountResponse> {
        return pointService.charge(UserIdHeader.require(userId), request)
            .let { PointAccountResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping
    override fun getBalance(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
    ): ApiResponse<PointAccountResponse> {
        return pointService.findBalance(UserIdHeader.require(userId))
            .let { PointAccountResponse.from(it) }
            .let { ApiResponse.success(it) }
    }
}
