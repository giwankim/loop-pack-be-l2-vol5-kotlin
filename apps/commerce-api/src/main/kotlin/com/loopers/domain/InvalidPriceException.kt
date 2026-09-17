package com.loopers.domain

/**
 * 금액이 규칙을 벗어난다. 음수 금액, 계산 넘침, 상품 가격 범위 밖이 여기에 든다.
 * 이 조각에서 금액이 쓰이는 곳이 가격뿐이라 금액 자체의 예외를 따로 두지 않는다.
 */
class InvalidPriceException(
    message: String,
) : RuleViolationException(message)
