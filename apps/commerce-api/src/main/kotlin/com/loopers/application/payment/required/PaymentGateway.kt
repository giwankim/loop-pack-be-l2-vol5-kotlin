package com.loopers.application.payment.required

import com.loopers.domain.payment.Card
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money

/**
 * PG에 PG 거래를 요청하고 조회하는 포트(ADR 0021). 구현은 `adapter.integration.pg`의 로컬 테스트 PG 어댑터다.
 *
 * PG의 결과를 던지지 않고 값으로 돌려준다(ADR 0022 규칙 4). 정확한 생성 전 거절만 [PaymentRequestResult.Rejected]다.
 * 시간 초과, 연결 끊김, 연결 풀의 대기 초과, 정확하지 않은 422를 비롯한 다른 상태 코드, 읽을 수 없는 응답은 모두
 * 생성이면 [PaymentRequestResult.Unknown], 조회면 [TransactionLookupResult.Unavailable]이다. 설정 누락 같은 프로그래밍 오류만
 * 그대로 던진다.
 */
interface PaymentGateway {
    /**
     * [pgOrderId]의 PG 거래를 [amount]만큼 [card]로 만들어 달라고 한 번 요청한다. 다시 보내지 않는다(ADR 0022 규칙 1).
     * 받아들여진 생성은 [PgTransaction]을 담지만 성공이 아니다. PG 상태는 그 PG 거래가 말한다.
     */
    fun requestPayment(pgOrderId: String, amount: Money, card: Card): PaymentRequestResult

    /** 거래 키 [transactionKey]의 PG 거래를 한 번 조회한다. PG가 그 PG 거래를 모르면 빈 [TransactionLookupResult.Found]다. */
    fun retrieveTransaction(transactionKey: String): TransactionLookupResult

    /** PG 주문 번호 [pgOrderId]의 PG 거래를 모두 한 번 조회한다. 없으면 빈 [TransactionLookupResult.Found]이고, 둘 이상일 수도 있다. */
    fun listTransactions(pgOrderId: String): TransactionLookupResult
}
