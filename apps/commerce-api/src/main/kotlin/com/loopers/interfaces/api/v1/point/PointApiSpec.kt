package com.loopers.interfaces.api.v1.point

import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.IdempotencyKeyHeader
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
            "정수 표기의 JSON 숫자로 1 이상이어야 합니다. 숫자 문자열·소수·지수 표기·누락·null은 400입니다. " +
            "Idempotency-Key 헤더는 필수이며 영문·숫자·하이픈·밑줄 1–128자입니다. 같은 키와 같은 amount의 재요청은 " +
            "첫 응답을 다시 주고, 같은 키에 다른 amount는 409입니다. 헤더가 없거나 그 사용자가 없으면 401입니다.",
    )
    fun charge(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long?,
        @Parameter(
            name = IdempotencyKeyHeader.NAME,
            `in` = ParameterIn.HEADER,
            description = "충전 요청을 구별하는 키. 영문·숫자·하이픈·밑줄 1–128자, 대소문자 구분",
            required = true,
        )
        idempotencyKey: String?,
        body: PointChargeRequestBody,
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
