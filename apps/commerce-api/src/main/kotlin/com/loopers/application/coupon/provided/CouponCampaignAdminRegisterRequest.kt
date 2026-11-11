package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.CouponCampaign
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * The input of registering a campaign. The web adapter binds the HTTP body to it directly. The controller and the port check the
 * constraints with `@Valid`, and the entity checks the same rules itself, so they are written in both places.
 */
data class CouponCampaignAdminRegisterRequest(
    @NotBlank(message = "쿠폰 캠페인 이름은 공백일 수 없습니다.")
    @Size(max = CouponCampaign.NAME_MAX_LENGTH, message = "쿠폰 캠페인 이름은 {max}자 이하여야 합니다.")
    val name: String,
    @Min(CouponCampaign.MIN_DISCOUNT_AMOUNT, message = "할인액은 {value}원 이상이어야 합니다.")
    val discountAmount: Long,
    @Min(CouponCampaign.MIN_TOTAL_QUOTA, message = "발급 한도는 {value} 이상이어야 합니다.")
    val totalQuota: Int,
)
