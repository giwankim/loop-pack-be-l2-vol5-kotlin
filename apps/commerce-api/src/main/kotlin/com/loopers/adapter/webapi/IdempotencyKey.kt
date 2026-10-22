package com.loopers.adapter.webapi

/**
 * 멱등 키를 받을 핸들러 파라미터에 단다(`CONTEXT.md` 멱등 키). [IdempotencyKeyArgumentResolver]가 `Idempotency-Key` 헤더를 읽어
 * 결제의 키 규칙에 맞는 키만 넘기므로 파라미터는 `String`이고 컨트롤러는 따로 검사하지 않는다.
 *
 * 본문보다 앞에 둔다. 인자는 파라미터 차례로 해석되므로, 그래야 키가 잘못된 요청을 본문을 읽기 전에 거절한다.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class IdempotencyKey
