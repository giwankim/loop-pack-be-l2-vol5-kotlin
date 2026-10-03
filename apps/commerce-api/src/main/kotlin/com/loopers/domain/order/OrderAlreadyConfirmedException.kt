package com.loopers.domain.order

import com.loopers.domain.shared.RuleViolationException

class OrderAlreadyConfirmedException : RuleViolationException("이미 확정된 주문입니다.")
