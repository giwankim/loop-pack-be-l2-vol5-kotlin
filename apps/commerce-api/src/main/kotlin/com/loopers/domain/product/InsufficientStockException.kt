package com.loopers.domain.product

import com.loopers.domain.shared.RuleViolationException

class InsufficientStockException : RuleViolationException("재고가 부족합니다.")
