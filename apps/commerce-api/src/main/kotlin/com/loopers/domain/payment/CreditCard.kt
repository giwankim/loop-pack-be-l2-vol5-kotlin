package com.loopers.domain.payment

/**
 * 결제에 쓰는 카드. PG에 그대로 넘기고 결제에 담지 않는다(`CONTEXT.md` 결제). 카드 종류는 문자열로 그대로 넘긴다.
 * 아직 검사하는 것이 없다. 무엇을 어디서 검사할지는 카드를 받는 확정 요청과 함께 2단계가 정한다.
 */
data class CreditCard(
    val type: String,
    val number: String,
)
