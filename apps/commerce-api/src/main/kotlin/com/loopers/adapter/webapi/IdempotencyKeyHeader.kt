package com.loopers.adapter.webapi

/**
 * 멱등 키 헤더(ADR 0025 규칙 1). 결제를 시작하는 확정이 받는다. IETF 초안(`draft-ietf-httpapi-idempotency-key-header`)과 Stripe, Toss가
 * 쓰는 이름이다. 읽고 검사하는 일은 [IdempotencyKeyArgumentResolver]가 하고, API 문서는 이 이름으로 헤더를 적는다.
 */
object IdempotencyKeyHeader {
    const val NAME = "Idempotency-Key"
}
