package com.loopers.domain.shared

/** 이름이 앞뒤 공백을 뗀 뒤 비어 있거나, 이름을 쓰는 엔티티의 길이 상한을 넘는다. */
class InvalidNameException(
    message: String,
) : RuleViolationException(message)
