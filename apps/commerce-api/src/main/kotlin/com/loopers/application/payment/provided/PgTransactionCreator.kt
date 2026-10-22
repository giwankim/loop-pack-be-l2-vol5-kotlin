package com.loopers.application.payment.provided

import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PgTransaction

/**
 * 결제의 PG 거래를 PG에 만들어 달라고 요청한다(`CONTEXT.md` PG 거래를 만들다). 트랜잭션 밖에서만 돈다(ADR 0028 규칙 2).
 * PG가 답하는 동안 DB 트랜잭션도 잠금도 쥐지 않는다(ADR 0024 규칙 1).
 */
interface PgTransactionCreator {
    /**
     * [payment]의 PG 주문 번호와 금액, [card]로 PG 거래의 생성을 한 번 보낸다. 다시 보내지 않는다(ADR 0024 규칙 3).
     * 받아들여진 생성의 PG 거래를 돌려주지만 성공이 아니다. 그 PG 거래를 결제에 반영하는 것은 [PaymentReconciler]다.
     *
     * @throws PgRejectedException 정확한 생성 전 거절. PG 거래가 없다.
     * @throws PgOutcomeUnknownException 그 밖의 모든 응답과 답 없음. 실패가 아니다.
     * @throws org.springframework.transaction.IllegalTransactionStateException 트랜잭션 안에서 불렀다. PG에 보내지 않는다.
     */
    fun create(payment: Payment, card: CreditCard): PgTransaction
}
