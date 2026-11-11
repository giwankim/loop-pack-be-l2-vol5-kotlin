package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Coupon V1 API", description = "고객 쿠폰 API 입니다. 요청자는 X-USER-ID 헤더로 식별합니다.")
interface CouponApiSpec {
    @Operation(
        summary = "쿠폰 발급",
        description = "쿠폰 캠페인에 쿠폰 발급을 요청합니다. 처음 발급하면 201, 이미 가진 쿠폰이면 200으로 그 쿠폰을 돌려주며 본문은 같습니다. " +
            "쿠폰 캠페인이 없으면 404 COUPON_CAMPAIGN_NOT_FOUND, 발급이 중지되었으면 409 COUPON_CAMPAIGN_SUSPENDED, " +
            "발급 한도가 소진되었으면 409 COUPON_CAMPAIGN_EXHAUSTED입니다. 이미 가진 쿠폰은 발급 중지나 소진 뒤에도 200으로 돌려줍니다.",
    )
    fun issue(
        userId: Long,
        campaignId: Long,
    ): ResponseEntity<ApiResponse<CouponResponse>>
}
