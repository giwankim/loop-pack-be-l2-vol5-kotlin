package com.loopers.domain.shared

/** 금액이 음수이거나 가진 금액보다 큰 금액을 뺀다. 금액을 쓰는 모든 개념이 같은 규칙을 쓴다. */
class InvalidMoneyException(
    message: String,
) : RuleViolationException(message)
