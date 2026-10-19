package com.loopers.application.payment.required

import com.loopers.domain.payment.Card
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money

/**
 * PG에 PG 거래를 요청하는 포트(ADR 0021). 구현은 `adapter.integration.pg`의 로컬 테스트 PG 어댑터다.
 *
 * PG의 결과를 던지지 않고 값으로 돌려준다(ADR 0022 규칙 4). 시간 초과, 연결 끊김, 다른 상태 코드, 읽을 수 없는 응답은 모두
 * [PaymentRequestResult.Unknown]이다. 설정 누락 같은 프로그래밍 오류만 그대로 던진다.
 */
interface PaymentGateway {
    /**
     * [pgOrderId]의 PG 거래를 [amount]만큼 [card]로 만들어 달라고 한 번 요청한다. 다시 보내지 않는다(ADR 0022 규칙 1).
     * 받아들여진 생성은 [PgTransaction]을 담지만 성공이 아니다. PG 상태는 그 거래가 말한다.
     */
    fun requestPayment(pgOrderId: String, amount: Money, card: Card): PaymentRequestResult
}
