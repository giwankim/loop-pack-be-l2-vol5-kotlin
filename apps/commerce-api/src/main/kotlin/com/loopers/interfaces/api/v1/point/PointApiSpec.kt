package com.loopers.interfaces.api.v1.point

import com.loopers.application.point.PointChargeRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.UserIdHeader
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Point V1 API", description = "고객 포인트 API 입니다. X-USER-ID 헤더의 사용자 식별자로 요청자를 식별합니다.")
interface PointApiSpec {
    @Operation(
        summary = "포인트 충전",
        description = "요청자의 잔액에 amount만큼 더하고 그 충전 직후의 잔액을 줍니다. 1포인트는 1원이며 amount는 " +
            "1 이상의 정수여야 합니다. 숫자 문자열과 소수 표기도 받고 소수부는 버립니다. 누락·null·숫자가 아닌 값·범위 밖은 400입니다. " +
            "요청마다 새 충전이므로 같은 요청을 다시 보내면 다시 충전됩니다. 헤더가 없거나 그 사용자가 없으면 401입니다.",
    )
    fun charge(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long?,
        request: PointChargeRequest,
    ): ApiResponse<PointAccountResponse>

    @Operation(
        summary = "잔액 조회",
        description = "요청자의 현재 잔액을 줍니다. 헤더가 없거나 그 사용자가 없으면 401입니다.",
    )
    fun getBalance(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long?,
    ): ApiResponse<PointAccountResponse>
}
