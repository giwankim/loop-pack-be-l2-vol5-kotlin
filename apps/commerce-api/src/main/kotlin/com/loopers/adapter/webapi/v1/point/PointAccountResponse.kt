package com.loopers.adapter.webapi.v1.point

import com.loopers.domain.point.PointAccount

/**
 * 고객 포인트 응답. 충전에서는 그 충전 직후의 잔액, 조회에서는 현재 잔액이다(설계 6).
 * 요청자 자신의 잔액만 보이므로 사용자 식별자는 싣지 않는다.
 */
data class PointAccountResponse(
    val balance: Long,
) {
    companion object {
        fun from(account: PointAccount): PointAccountResponse = PointAccountResponse(balance = account.balance.amount)
    }
}
