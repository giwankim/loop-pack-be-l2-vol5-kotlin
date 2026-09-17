package com.loopers.domain.product

import com.loopers.domain.shared.RuleViolationException

/** 상품 가격이 1원 이상 1,000,000,000원 이하 범위를 벗어난다. 금액 자체의 규칙은 [com.loopers.domain.shared.InvalidMoneyException]이 맡는다. */
class InvalidPriceException(
    message: String,
) : RuleViolationException(message)
