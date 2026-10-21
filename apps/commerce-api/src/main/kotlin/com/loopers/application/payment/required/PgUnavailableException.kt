package com.loopers.application.payment.required

/**
 * PG에게서 읽을 수 있는 조회의 답을 얻지 못했다. 시간 초과, 연결 끊김, 다른 상태 코드, 읽을 수 없는 응답이다(ADR 0022 규칙 3, ADR 0023).
 * PG 거래가 없다는 뜻이 아니다. 호출자는 저장된 상태를 돌려주고, 모든 조회 불가를 같게 다룬다.
 *
 * [message]는 HTTP 상태(`HTTP 503`)나 실패의 종류(`SocketTimeoutException`)이고, 로그와 테스트의 단언에만 쓴다. 원인 예외를 잇지 않는다.
 * 해석 오류의 메시지는 응답 본문의 조각을 담을 수 있다(결제 설계 3.5).
 */
class PgUnavailableException(
    override val message: String,
) : RuntimeException(message)
