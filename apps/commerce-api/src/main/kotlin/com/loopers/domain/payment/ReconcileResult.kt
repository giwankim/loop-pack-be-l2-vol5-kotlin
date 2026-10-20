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

    /** 결제와 맞지 않는 답이다. 결제는 그 [reason]과 시각을 마지막 충돌로 남기고 상태와 거래 키를 그대로 둔다. */
    data class Conflicted(val reason: ConflictReason) : ReconcileResult
}
