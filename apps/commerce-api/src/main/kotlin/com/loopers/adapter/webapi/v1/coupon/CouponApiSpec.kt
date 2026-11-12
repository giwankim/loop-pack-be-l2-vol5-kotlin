package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.application.coupon.provided.CouponListRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Coupon V1 API", description = "고객 쿠폰 API 입니다. 요청자는 X-USER-ID 헤더로 식별합니다.")
interface CouponApiSpec {
    @Operation(
        summary = "쿠폰 발급",
        description = "쿠폰 캠페인에 쿠폰 발급을 요청합니다. 처음 발급하면 201, 이미 가진 쿠폰이면 200으로 그 쿠폰을 돌려주며 본문은 같습니다. " +
            "쿠폰 캠페인이 없으면 404 COUPON_CAMPAIGN_NOT_FOUND, 발급이 중지되었으면 409 COUPON_CAMPAIGN_SUSPENDED, " +
            "발급 한도가 소진되었으면 409 COUPON_CAMPAIGN_EXHAUSTED입니다. 이미 가진 쿠폰은 발급 중지나 소진 뒤에도 200으로 돌려줍니다. " +
            "발급을 처리할 자리가 모두 찼으면 기다리지 않고 429 COUPON_ISSUE_CAPACITY_EXCEEDED로 답하며 아무것도 발급하지 않으니, 다시 보내도 됩니다. " +
            "이미 가진 쿠폰은 자리가 찼어도 200으로 돌려줍니다.",
    )
    fun issue(
        userId: Long,
        campaignId: Long,
    ): ResponseEntity<ApiResponse<CouponResponse>>

    @Operation(
        summary = "내 쿠폰 목록 조회",
        description = "요청자의 쿠폰을 한 조각씩 조회합니다. 최근에 발급된 쿠폰이 앞서고, 발급 시각이 같으면 쿠폰 ID가 큰 쿠폰이 앞섭니다. " +
            "쿠폰 캠페인의 이름과 할인액은 발급 당시의 것이며, 소진되거나 발급이 중지된 쿠폰 캠페인의 쿠폰도 나옵니다. " +
            "page는 0 이상, size는 1 이상 100 이하이며, 주지 않으면 page=0, size=20입니다. " +
            "총 개수 대신 다음 조각의 존재(hasNext)를 줍니다. 헤더가 없거나 그 사용자가 없으면 401입니다.",
    )
    fun getCoupons(
        userId: Long,
        request: CouponListRequest,
    ): ApiResponse<PageResponse<CouponResponse>>
}
