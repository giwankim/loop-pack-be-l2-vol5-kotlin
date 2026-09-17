package com.loopers.domain.shared

/** 브랜드나 상품의 이름이 앞뒤 공백을 뗀 뒤 비어 있거나, 그 엔티티의 길이 상한을 넘는다. */
class InvalidNameException(
    message: String,
) : RuleViolationException(message)
