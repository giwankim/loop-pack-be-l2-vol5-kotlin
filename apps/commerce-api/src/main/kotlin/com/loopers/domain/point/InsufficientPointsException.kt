package com.loopers.domain.point

import com.loopers.domain.shared.RuleViolationException

class InsufficientPointsException : RuleViolationException("포인트가 부족합니다.")
