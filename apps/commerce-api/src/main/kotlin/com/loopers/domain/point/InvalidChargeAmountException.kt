package com.loopers.domain.point

import com.loopers.domain.shared.RuleViolationException

/** 충전액이 양수가 아니다. 충전 후 잔액의 넘침은 [com.loopers.domain.shared.InvalidMoneyException]이 거절한다. */
class InvalidChargeAmountException(
    message: String,
) : RuleViolationException(message)
