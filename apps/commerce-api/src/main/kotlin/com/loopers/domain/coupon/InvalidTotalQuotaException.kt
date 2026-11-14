package com.loopers.domain.coupon

import com.loopers.domain.shared.RuleViolationException

/** A campaign's total quota is below 1. */
class InvalidTotalQuotaException(
    message: String,
) : RuleViolationException(message)
