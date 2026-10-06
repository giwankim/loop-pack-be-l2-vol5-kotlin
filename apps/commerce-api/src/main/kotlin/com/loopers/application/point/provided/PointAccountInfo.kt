package com.loopers.application.point.provided

import com.loopers.domain.point.PointAccount

/**
 * 포인트 계정 응답 모델. 요청자 자신의 잔액만 보이므로 사용자 식별자는 싣지 않는다.
 * 충전 응답의 [balance]는 그 충전 직후의 잔액이고, 조회 응답의 [balance]는 현재 잔액이다(설계 6).
 */
data class PointAccountInfo(
    val balance: Long,
) {
    companion object {
        fun from(account: PointAccount): PointAccountInfo = PointAccountInfo(balance = account.balance.amount)
    }
}
