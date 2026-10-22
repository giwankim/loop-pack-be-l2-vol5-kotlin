package com.loopers.application.payment.provided

import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.ReconcileResult

/**
 * PG가 말한 PG 거래나 생성 전 거절을 결제에 반영한다(`CONTEXT.md` 대사하다). 주문 확정과 대사의 TX B가 주문을 잠근 뒤 부르고, 그 트랜잭션에 참여한다.
 * 결제를 바꾸는 트랜잭션은 모두 주문을 먼저 잠그므로 결제 행은 잠그지 않고 읽는다(ADR 0028 규칙 4).
 *
 * 맞지 않는 답은 결제를 바꾸지 않고 [ReconcileResult.Conflicted]로 돌려주며 WARN을 한 줄 남긴다. 예외로 던지면 참여한 TX B가
 * rollback-only가 되므로 던지지 않는다(ADR 0028 규칙 3). 카드 번호는 받지 않으므로 로그에도 없다.
 */
interface PaymentReconciler {
    /** 주문 [orderId]의 결제를 PG 거래 [transaction]과 대사하고 그 결과를 돌려준다(ADR 0021 규칙 2). 결제가 없으면 `PAYMENT_MISSING`이다. */
    fun reconcile(orderId: Long, transaction: PgTransaction): ReconcileResult

    /**
     * 주문 [orderId]의 결제에 생성 전 거절 [rejection]을 반영하고 그 결과를 돌려준다(ADR 0022). 결과 미확인 결제는 실패로 바뀌고, 이미 실패한
     * 결제는 그대로다. 결제가 없으면 `PAYMENT_MISSING`이다.
     */
    fun reject(orderId: Long, rejection: PgRejection): ReconcileResult
}
