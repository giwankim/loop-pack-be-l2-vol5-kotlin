package com.loopers.domain.coupon

import com.loopers.domain.shared.RuleViolationException

/** A campaign's discount amount is below 1원. The rules of an amount itself belong to [com.loopers.domain.shared.InvalidMoneyException]. */
class InvalidDiscountAmountException(
    message: String,
) : RuleViolationException(message)
