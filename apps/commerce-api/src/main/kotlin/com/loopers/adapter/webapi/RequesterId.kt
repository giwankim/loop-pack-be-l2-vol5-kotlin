package com.loopers.adapter.webapi

/**
 * 요청자의 사용자 식별자를 받을 핸들러 파라미터에 단다(CONTEXT.md 요청자). [RequesterIdArgumentResolver]가
 * `X-USER-ID` 헤더를 읽어 받아들인 요청자만 넘기므로 파라미터는 `Long`이고 컨트롤러는 따로 확인하지 않는다(ADR 0015).
 *
 * 본문이나 쿼리 문자열보다 앞에 둔다. 인자는 파라미터 차례로 해석되므로, 그래야 요청자가 없는 요청을 본문을 읽기 전에 거절한다.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequesterId
