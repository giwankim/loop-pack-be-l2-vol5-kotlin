package com.loopers.application.payment.provided

import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PgTransaction

/**
 * 결제의 PG 거래를 PG에 조회한다. 트랜잭션 밖에서만 돈다(ADR 0028 규칙 2). PG가 답하는 동안 DB 트랜잭션도 잠금도 쥐지 않는다.
 * 조회는 PG 거래를 만들지 않는다(ADR 0024 규칙 3).
 */
interface PgTransactionRetriever {
    /**
     * [payment]의 PG 주문 번호로 PG 거래를 한 번 조회한다. PG가 그 번호의 PG 거래를 하나도 말하지 않으면 `null`이다. `null`은 PG 거래가
     * 없다는 증거가 아니다. PG 거래가 아직 보이지 않거나 생성이 아직 오는 중일 수 있다(ADR 0022 규칙 3). 찾은 PG 거래가 결제와 맞는지는
     * [PaymentReconciler]가 판단한다.
     *
     * @throws MultiplePgTransactionsException PG가 그 PG 주문 번호의 PG 거래를 둘 이상 말했다. 하나를 고르지 않는다.
     * @throws PgUnavailableException 그 밖의 모든 응답과 답 없음. PG 거래가 없다는 뜻이 아니다.
     * @throws org.springframework.transaction.IllegalTransactionStateException 트랜잭션 안에서 불렀다. PG에 조회하지 않는다.
     */
    fun retrieve(payment: Payment): PgTransaction?
}
