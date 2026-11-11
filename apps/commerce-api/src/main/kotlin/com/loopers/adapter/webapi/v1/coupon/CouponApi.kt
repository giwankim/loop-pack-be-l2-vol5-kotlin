package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.RequesterId
import com.loopers.application.coupon.provided.CouponIssuer
import com.loopers.support.stereotype.WebApiAdapter
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

/** The customer's coupons. The holder is always the requester ([RequesterId]), and no request field can name another user. */
@WebApiAdapter
@RequestMapping("/api/v1")
class CouponApi(
    private val couponIssuer: CouponIssuer,
) : CouponApiSpec {
    /** A repeat request gets the same body, so only the status tells whether this request created the coupon. */
    @PostMapping("/coupon-campaigns/{campaignId}/issues")
    override fun issue(
        @RequesterId userId: Long,
        @PathVariable campaignId: Long,
    ): ResponseEntity<ApiResponse<CouponResponse>> {
        val result = couponIssuer.issue(userId, campaignId)
        val status = if (result.newlyIssued) HttpStatus.CREATED else HttpStatus.OK

        return ResponseEntity.status(status).body(ApiResponse.success(CouponResponse.from(result.coupon)))
    }
}
