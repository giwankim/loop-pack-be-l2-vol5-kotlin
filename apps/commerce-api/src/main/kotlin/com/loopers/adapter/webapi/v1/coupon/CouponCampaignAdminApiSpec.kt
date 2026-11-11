package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.application.coupon.provided.CouponCampaignAdminRegisterRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Coupon Campaign Admin V1 API", description = "관리자 쿠폰 캠페인 API 입니다. ADMIN 역할이 필요합니다.")
interface CouponCampaignAdminApiSpec {
    @Operation(
        summary = "쿠폰 캠페인 등록",
        description = "이름, 할인액, 발급 한도로 쿠폰 캠페인을 등록합니다. 이름은 공백뿐일 수 없고 100자 이하이며, " +
            "할인액은 1원 이상, 발급 한도는 1 이상입니다. 등록된 쿠폰 캠페인의 발급 수는 0이고 발급 중지가 아닙니다.",
    )
    fun register(
        request: CouponCampaignAdminRegisterRequest,
    ): ApiResponse<CouponCampaignAdminResponse>

    @Operation(
        summary = "쿠폰 캠페인 발급 중지",
        description = "쿠폰 캠페인의 발급을 중지하고 그 쿠폰 캠페인을 돌려줍니다. 되돌릴 수 없고, 이미 발급 중지면 바꾸는 것 없이 200입니다. " +
            "이미 발급된 쿠폰은 그대로입니다. 쿠폰 캠페인이 없으면 404 COUPON_CAMPAIGN_NOT_FOUND입니다.",
    )
    fun suspend(
        campaignId: Long,
    ): ApiResponse<CouponCampaignAdminResponse>
}
