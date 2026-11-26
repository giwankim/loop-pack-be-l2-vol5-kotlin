package com.loopers.application.payment.provided

/**
 * How many 끝나지 않은 결제 there are, by payment status (`CONTEXT.md` 끝나지 않은 결제): [unknown] payments are 결과 미확인,
 * [pending] payments are 처리 중. A status with no payment counts 0.
 */
data class UnresolvedPaymentCount(
    val unknown: Long,
    val pending: Long,
)
