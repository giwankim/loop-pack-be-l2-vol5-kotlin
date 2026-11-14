package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponFinder
import com.loopers.application.coupon.provided.CouponListRequest
import com.loopers.application.coupon.required.CouponRepository
import com.loopers.domain.coupon.Coupon
import com.loopers.support.stereotype.ValidatedApplicationService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice

/** The implementation of [CouponFinder]. It validates because the list takes a Request. */
@ValidatedApplicationService(readOnly = true)
class CouponQueryService(
    private val couponRepository: CouponRepository,
) : CouponFinder {
    override fun findAll(userId: Long, request: CouponListRequest): Slice<Coupon> {
        val pageable = PageRequest.of(request.page, request.size)

        return couponRepository.findAllByUserIdOrderByIssuedAtDescIdDesc(userId, pageable)
    }
}
