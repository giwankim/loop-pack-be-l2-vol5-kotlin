package com.loopers.application.payment.provided

import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.ReconcileResult
import com.loopers.domain.shared.Money

/**
 * 커머스가 남기는 결제의 기록을 쓴다(`CONTEXT.md` 결제). 주문 확정의 TX A가 결제를 만들고([start]), 확정과 대사의 TX B가 주문을 잠근 뒤
 * PG가 말한 PG 거래나 생성 전 거절을 그 결제에 반영한다([reconcile], [reject]). 모두 부르는 쪽의 트랜잭션에 참여한다(ADR 0028 규칙 1).
 * 결제를 바꾸는 트랜잭션은 모두 주문을 먼저 잠그므로 결제 행은 잠그지 않고 읽는다(ADR 0028 규칙 4). PG는 부르지 않는다. PG에 닿는 것은
 * [PgTransactionClient]다.
 *
 * 결제의 시작과 반영은 유스케이스가 다르지만 포트 하나에 둔다. 구현도, 부르는 조율 서비스도, 부르는 쪽의 트랜잭션에 참여한다는 조건도 같다.
 */
interface PaymentRecorder {
    /**
     * 주문 [orderId]의 결제를 결과 미확인으로 만들어 저장하고 돌려준다. PG 주문 번호는 결제가 생성기로 새로 만든다(ADR 0029).
     * TX A에 참여하므로 결제는 재고 예약, 결제 중인 주문과 함께 커밋되고 함께 되돌아간다. 그 커밋이 발송 기록이다(ADR 0024 규칙 2).
     * 멱등 키나 금액이 결제의 규칙에 맞지 않거나, 금액이 PG가 받는 64비트 정수의 범위를 넘으면
     * [com.loopers.domain.payment.InvalidPaymentException]을 던진다. 부르는 쪽의 트랜잭션이 예약과 함께 되돌아간다.
     *
     * 키가 쓰였는지는 묻지 않는다. 부르는 쪽이 먼저 [PaymentValidator.validateForStart]로 본다. 그 뒤에 다른 결제가 같은 키를 커밋했으면
     * 삽입이 `UK_PAYMENT_ON_IDEMPOTENCY_KEY`에 걸려 `DataIntegrityViolationException`이 난다(ADR 0025 규칙 3).
     */
    fun start(orderId: Long, idempotencyKey: String, amount: Money): Payment

    /**
     * 주문 [orderId]의 결제를 PG 거래 [transaction]과 대사하고 그 결과를 돌려준다(`CONTEXT.md` 대사하다, ADR 0021 규칙 2). 결제가 없으면
     * `PAYMENT_MISSING`이다.
     *
     * 맞지 않는 답은 결제를 바꾸지 않고 [ReconcileResult.Conflicted]로 돌려주며 WARN을 한 줄 남긴다. 예외로 던지면 참여한 TX B가
     * rollback-only가 되므로 던지지 않는다(ADR 0028 규칙 3). 카드 번호는 받지 않으므로 로그에도 없다. [reject]도 같다.
     */
    fun reconcile(orderId: Long, transaction: PgTransaction): ReconcileResult

    /**
     * 주문 [orderId]의 결제에 생성 전 거절 [rejection]을 반영하고 그 결과를 돌려준다(ADR 0022). 결과 미확인 결제는 실패로 바뀌고, 이미 실패한
     * 결제는 그대로다. 결제가 없으면 `PAYMENT_MISSING`이다.
     */
    fun reject(orderId: Long, rejection: PgRejection): ReconcileResult
}
