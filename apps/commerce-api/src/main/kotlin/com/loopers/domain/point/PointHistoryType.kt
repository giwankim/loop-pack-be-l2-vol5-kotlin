package com.loopers.domain.point

/** 잔액을 바꾼 성공의 종류. 결제(PAYMENT)는 주문 확정 조각에서 더한다. */
enum class PointHistoryType {
    /** 충전. 충전 키와 충전액, 충전 직후 잔액을 남긴다. */
    CHARGE,
}
