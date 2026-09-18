package com.loopers.interfaces.api.v1.point

import com.loopers.application.point.PointAccountInfo

/** 고객 포인트 응답. 충전에서는 그 충전 직후의 잔액, 조회에서는 현재 잔액이다(설계 6). */
data class PointAccountResponse(
    val balance: Long,
) {
    companion object {
        fun from(info: PointAccountInfo): PointAccountResponse = PointAccountResponse(balance = info.balance)
    }
}
