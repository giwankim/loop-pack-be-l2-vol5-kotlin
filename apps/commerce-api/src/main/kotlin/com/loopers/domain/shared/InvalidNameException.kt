package com.loopers.domain.shared

/** 브랜드나 상품의 이름이 공백뿐이거나, 받은 그대로 세어 그 엔티티의 길이 상한을 넘는다. */
class InvalidNameException(
    message: String,
) : RuleViolationException(message)
