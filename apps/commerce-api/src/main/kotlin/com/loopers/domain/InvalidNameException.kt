package com.loopers.domain

/** 이름이 앞뒤 공백을 뗀 뒤 비어 있거나 너무 길다. 브랜드와 상품이 같은 규칙을 쓴다. */
class InvalidNameException(
    message: String,
) : RuleViolationException(message)
