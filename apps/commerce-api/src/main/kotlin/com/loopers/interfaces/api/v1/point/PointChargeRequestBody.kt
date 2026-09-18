package com.loopers.interfaces.api.v1.point

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.loopers.application.point.PointChargeRequest
import com.loopers.interfaces.api.StrictLongDeserializer
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 충전 요청의 HTTP 본문. 카탈로그와 달리 application의 Request를 본문으로 바로 받지 않는다. JSON 토큰의 종류를
 * 가리는 정책은 HTTP의 것이라 Request에 Jackson 애노테이션을 두지 않으려는 것이다(설계 5.10, 12.3).
 *
 * [amount]가 null인 것은 값이 `null`이거나 빠진 것이다. 둘 다 400 `INVALID_POINT_ORDER_REQUEST`다.
 * 충전 키는 본문이 아니라 헤더에서 오므로 [toRequest]가 받아 Request를 만든다.
 */
data class PointChargeRequestBody(
    @JsonDeserialize(using = StrictLongDeserializer::class)
    val amount: Long?,
) {
    fun toRequest(chargeKey: String): PointChargeRequest =
        PointChargeRequest(
            chargeKey = chargeKey,
            amount = amount ?: throw CoreException(ErrorType.INVALID_POINT_ORDER_REQUEST),
        )
}
