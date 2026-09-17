package com.loopers.domain.product

import com.loopers.domain.shared.RuleViolationException

/** 재고 수량이 0보다 작다. */
class InvalidStockException(
    message: String,
) : RuleViolationException(message)
