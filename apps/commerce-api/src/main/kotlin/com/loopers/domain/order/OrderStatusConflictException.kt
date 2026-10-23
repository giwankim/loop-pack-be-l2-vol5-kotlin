package com.loopers.domain.order

import com.loopers.domain.shared.RuleViolationException

class OrderStatusConflictException(message: String) : RuleViolationException(message)
