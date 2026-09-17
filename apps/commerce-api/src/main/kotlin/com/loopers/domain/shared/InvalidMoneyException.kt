package com.loopers.domain.shared

/** 금액이 음수가 되거나 계산 결과가 `Long` 범위를 넘는다. 금액을 쓰는 모든 개념이 같은 규칙을 쓴다. */
class InvalidMoneyException(
    message: String,
) : RuleViolationException(message)
