package com.loopers.domain.payment

import com.loopers.domain.shared.RuleViolationException

/** 결제를 만들 수 없는 멱등 키나 금액. */
class InvalidPaymentException(message: String) : RuleViolationException(message)
