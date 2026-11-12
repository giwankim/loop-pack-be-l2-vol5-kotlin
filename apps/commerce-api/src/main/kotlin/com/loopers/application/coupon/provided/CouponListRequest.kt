package com.loopers.application.coupon.provided

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * The input of the own-coupon list. The user ID comes from the requester, so it is not here but a parameter of [CouponFinder.findAll].
 * There is no sort to choose: the list has one order, newest first.
 *
 * The bounds and the messages are those of [com.loopers.application.like.provided.LikeListRequest] and
 * [com.loopers.application.order.provided.OrderListRequest]. Each list keeps a Request of its own, so that a change to one list's
 * bounds does not carry over to the others (catalog design 5.17).
 */
data class CouponListRequest(
    @Min(0, message = "page는 0 이상이어야 합니다.")
    val page: Int = DEFAULT_PAGE,
    @Min(1, message = "size는 1 이상이어야 합니다.")
    @Max(MAX_SIZE.toLong(), message = "size는 {value} 이하여야 합니다.")
    val size: Int = DEFAULT_SIZE,
) {
    companion object {
        const val DEFAULT_PAGE = 0
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}
