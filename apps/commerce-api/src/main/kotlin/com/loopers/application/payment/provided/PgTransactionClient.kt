package com.loopers.application.payment.provided

import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PgTransaction

/**
 * 결제의 PG 거래를 PG에 만들어 달라고 요청하고(`CONTEXT.md` PG 거래를 만들다), 결제의 PG 주문 번호로 조회한다. 둘 다 트랜잭션 밖에서만
 * 돈다(ADR 0028 규칙 2). PG가 답하는 동안 DB 트랜잭션도 잠금도 쥐지 않는다(ADR 0024 규칙 1).
 *
 * 주문 슬라이스는 PG에 닿는 required 포트 [com.loopers.application.payment.required.PaymentGateway]를 부르지 않고 이 포트를 부른다.
 * 다른 슬라이스의 required 포트는 쓰지 않고(ADR 0013), 이 포트의 구현이 트랜잭션 안의 호출을 PG에 보내기 전에 거절하며(ADR 0028 규칙 2),
 * 결제의 PG 주문 번호와 금액을 PG 요청으로 옮기는 일은 결제 슬라이스에 남는다(ADR 0028 규칙 5).
 *
 * 확정의 생성과 대사의 조회는 유스케이스가 다르지만 포트 하나에 둔다. 구현도, 부르는 조율 서비스도, 트랜잭션 밖이라는 조건도 같다.
 */
interface PgTransactionClient {
    /**
     * [payment]의 PG 주문 번호와 금액, [card]로 PG 거래의 생성을 한 번 보낸다. 다시 보내지 않는다(ADR 0024 규칙 3).
     * 받아들여진 생성의 PG 거래를 돌려주지만 성공이 아니다. 그 PG 거래를 결제에 반영하는 것은 [PaymentRecorder.reconcile]이다.
     *
     * @throws PgRejectedException 정확한 생성 전 거절. PG 거래가 없다.
     * @throws PgOutcomeUnknownException 그 밖의 모든 응답과 답 없음. 실패가 아니다.
     * @throws org.springframework.transaction.IllegalTransactionStateException 트랜잭션 안에서 불렀다. PG에 보내지 않는다.
     */
    fun create(payment: Payment, card: CreditCard): PgTransaction

    /**
     * [payment]의 PG 주문 번호로 PG 거래를 한 번 조회한다. 조회는 PG 거래를 만들지 않는다(ADR 0024 규칙 3). PG가 그 번호의 PG 거래를
     * 하나도 말하지 않으면 `null`이다. `null`은 PG 거래가 없다는 증거가 아니다. PG 거래가 아직 보이지 않거나 생성이 아직 오는 중일 수
     * 있다(ADR 0022 규칙 3). 찾은 PG 거래가 결제와 맞는지는 [PaymentRecorder.reconcile]이 판단한다.
     *
     * @throws MultiplePgTransactionsException PG가 그 PG 주문 번호의 PG 거래를 둘 이상 말했다. 하나를 고르지 않는다.
     * @throws PgUnavailableException 그 밖의 모든 응답과 답 없음. PG 거래가 없다는 뜻이 아니다.
     * @throws org.springframework.transaction.IllegalTransactionStateException 트랜잭션 안에서 불렀다. PG에 조회하지 않는다.
     */
    fun retrieve(payment: Payment): PgTransaction?
}
