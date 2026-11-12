package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.adapter.webapi.RequesterId
import com.loopers.application.coupon.provided.CouponFinder
import com.loopers.application.coupon.provided.CouponIssuer
import com.loopers.application.coupon.provided.CouponListRequest
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

/** The customer's coupons. The holder is always the requester ([RequesterId]), and no request field can name another user. */
@WebApiAdapter
@RequestMapping("/api/v1")
class CouponApi(
    private val couponIssuer: CouponIssuer,
    private val couponFinder: CouponFinder,
) : CouponApiSpec {
    /** A repeat request gets the same body, so only the status tells whether this request created the coupon. */
    @PostMapping("/coupon-campaigns/{campaignId}/issues")
    override fun issue(
        @RequesterId userId: Long,
        @PathVariable("campaignId") campaignId: Long,
    ): ResponseEntity<ApiResponse<CouponResponse>> {
        val result = couponIssuer.issue(userId, campaignId)
        val status = if (result.newlyIssued) HttpStatus.CREATED else HttpStatus.OK

        return ResponseEntity.status(status).body(ApiResponse.success(CouponResponse.from(result.coupon)))
    }

    /** The query string binds to [CouponListRequest] directly, as the other lists do (catalog design 5.17, 5.22). */
    @GetMapping("/coupons")
    override fun getCoupons(
        @RequesterId userId: Long,
        @ModelAttribute @Valid request: CouponListRequest,
    ): ApiResponse<PageResponse<CouponResponse>> {
        val coupons = couponFinder.findAll(userId = userId, request = request)

        return ApiResponse.success(PageResponse.from(coupons.map(CouponResponse::from)))
    }
}
