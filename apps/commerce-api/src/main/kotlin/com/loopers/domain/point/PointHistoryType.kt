package com.loopers.domain.point

/** 잔액을 바꾼 성공의 종류. */
enum class PointHistoryType {
    /** 충전. 충전 키와 충전액, 충전 직후 잔액을 남긴다. */
    CHARGE,

    /** 주문 결제. 주문 식별자와 결제액, 결제 직후 잔액을 남긴다. */
    PAYMENT,
}
