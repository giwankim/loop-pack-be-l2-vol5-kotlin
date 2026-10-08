package com.loopers.application.point.provided

import com.loopers.domain.shared.Money

/**
 * 주문 확정이 부르는 포인트 차감. 계정의 잔액은 포인트 조각만 바꾼다.
 * 부르는 쪽의 트랜잭션에 참여하므로 차감은 확정과 함께 커밋되고 함께 되돌아간다(ADR 0003).
 */
interface PointDeductor {
    /**
     * [userId] 사용자의 계정에서 [amount]를 차감한다. 계정이 없으면 `POINT_ACCOUNT_MISSING`을 던진다(설계 5.9, 6 끝).
     * 잔액이 모자라면 잔액을 그대로 두고 [com.loopers.domain.point.InsufficientPointsException]을 던진다.
     * 요청자는 웹 경계가 이미 받아들였으므로 묻지 않는다(ADR 0015).
     */
    fun deduct(userId: Long, amount: Money)
}
