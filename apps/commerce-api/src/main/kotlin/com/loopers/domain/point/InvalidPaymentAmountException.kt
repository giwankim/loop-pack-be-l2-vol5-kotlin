package com.loopers.domain.point

import com.loopers.domain.shared.RuleViolationException

class InvalidPaymentAmountException : RuleViolationException("결제액은 1원 이상이어야 합니다.")
