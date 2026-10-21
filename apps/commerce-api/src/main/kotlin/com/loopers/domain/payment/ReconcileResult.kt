package com.loopers.domain.payment

/**
 * [Payment.reconcile]과 [Payment.reject]의 결과(ADR 0021 규칙 2). 결과를 반영하는 응용 서비스(2단계)가 이 값을 보고
 * 주문과 예약을 바꾸거나 충돌을 로그로 남긴다.
 */
sealed interface ReconcileResult {
    /** 결제 상태가 [from]에서 [to]로 앞으로 갔다. */
    data class Changed(val from: PaymentStatus, val to: PaymentStatus) : ReconcileResult

    /** 결제가 그대로다. 아는 것이 늘지 않았거나 같은 답을 다시 들었다. */
    data object Unchanged : ReconcileResult

    /** 결제와 맞지 않는 답의 [reason]이다. 결제는 바뀌지 않고, 응용 서비스가 충돌을 로그로 남긴다(2단계). */
    data class Conflicted(val reason: ConflictReason) : ReconcileResult
}
