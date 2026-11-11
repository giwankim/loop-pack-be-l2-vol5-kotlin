package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.application.coupon.provided.CouponCampaignAdminRegisterRequest
import com.loopers.application.coupon.provided.CouponCampaignRegister
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus

@WebApiAdapter
@RequestMapping("/api-admin/v1/coupon-campaigns")
class CouponCampaignAdminApi(
    private val couponCampaignRegister: CouponCampaignRegister,
) : CouponCampaignAdminApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody @Valid request: CouponCampaignAdminRegisterRequest,
    ): ApiResponse<CouponCampaignAdminResponse> {
        val campaign = couponCampaignRegister.register(request)

        return ApiResponse.success(CouponCampaignAdminResponse.from(campaign))
    }
}
