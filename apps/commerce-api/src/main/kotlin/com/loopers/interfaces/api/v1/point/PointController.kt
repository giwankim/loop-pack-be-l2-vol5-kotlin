package com.loopers.interfaces.api.v1.point

import com.loopers.application.point.PointService
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.IdempotencyKeyHeader
import com.loopers.interfaces.api.UserIdHeader
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 고객 포인트. 요청자는 헤더에서 읽는다([UserIdHeader]). 충전은 [IdempotencyKeyHeader]의 키로 요청을 구별한다.
 * 본문은 [PointChargeRequestBody]가 받아 토큰의 종류를 가린 뒤 application Request로 옮긴다(설계 5.10).
 */
@RestController
@RequestMapping("/api/v1/points")
class PointController(
    private val pointService: PointService,
) : PointApiSpec {
    @PostMapping("/charge")
    override fun charge(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @RequestHeader(IdempotencyKeyHeader.NAME, required = false) chargeKey: String?,
        @RequestBody body: PointChargeRequestBody,
    ): ApiResponse<PointAccountResponse> {
        val requester = UserIdHeader.require(userId)
        val request = body.toRequest(chargeKey = IdempotencyKeyHeader.require(chargeKey))
        return pointService.charge(requester, request)
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
