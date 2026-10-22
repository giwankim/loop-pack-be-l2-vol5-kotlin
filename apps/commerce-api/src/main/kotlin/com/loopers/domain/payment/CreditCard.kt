package com.loopers.domain.payment

/**
 * 결제에 쓰는 카드. PG에 그대로 넘기고 결제에 담지 않는다(`CONTEXT.md` 결제). 카드 종류는 문자열로 그대로 넘긴다.
 * 이 값은 검사하지 않는다. 카드를 받는 확정 요청과 PG 포트의 생성 요청이 같은 제약으로 검사하고, 확정 요청의 검사가 재고를 예약하기 전에
 * 잘못된 카드를 거절한다(ADR 0024 규칙 1).
 */
data class CreditCard(
    val type: String,
    val number: String,
)
