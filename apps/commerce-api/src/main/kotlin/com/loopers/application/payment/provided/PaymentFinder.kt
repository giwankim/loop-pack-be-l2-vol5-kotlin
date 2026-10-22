package com.loopers.application.payment.provided

import com.loopers.domain.payment.Payment

/**
 * 결제 조각이 내주는 읽기. 결제는 주문을 식별자로만 가리키므로 주문 식별자로 찾는다(ADR 0021 규칙 1).
 * 주문의 결제수단이 PG일 때만 부른다. 확정 전 주문과 포인트로 결제한 주문에는 결제가 없다(ADR 0028 규칙 1).
 */
interface PaymentFinder {
    /** 주문 [orderId]의 결제. 결제수단이 PG인 주문에 결제가 없는 것은 데이터의 불일치이므로 `PAYMENT_MISSING`이다. */
    fun findByOrder(orderId: Long): Payment
}
