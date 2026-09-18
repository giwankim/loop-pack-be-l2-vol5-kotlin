package com.loopers.domain.order

import com.loopers.domain.shared.RuleViolationException

class InvalidOrderException(message: String) : RuleViolationException(message)
