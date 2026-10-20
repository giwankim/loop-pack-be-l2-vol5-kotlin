package com.loopers.application.payment.required

/**
 * 생성이 PG에서 어떻게 되었는지 모른다. PG 거래가 있을 수도, 이미 성공했을 수도 있다(ADR 0022 규칙 3, ADR 0023).
 * 실패가 아니다. 호출자는 결제를 결과 미확인으로 두고 저장된 상태를 돌려주며, 생성을 다시 보내지 않는다.
 *
 * [message]는 HTTP 상태(`HTTP 503`)나 실패의 종류(`SocketTimeoutException`)이고, 로그와 테스트의 단언에만 쓴다. 원인 예외를 잇지 않는다.
 * 해석 오류의 메시지는 응답 본문의 조각을 담을 수 있다(결제 설계 3.5).
 */
class PgOutcomeUnknownException(
    override val message: String,
) : RuntimeException(message)
