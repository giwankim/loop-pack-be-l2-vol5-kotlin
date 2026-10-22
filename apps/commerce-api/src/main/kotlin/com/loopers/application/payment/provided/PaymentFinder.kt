package com.loopers.application.payment.provided

import com.loopers.domain.payment.Payment

/**
 * 결제 조각이 내주는 읽기. 결제는 주문을 식별자로만 가리키므로 주문 식별자로 찾는다(ADR 0021 규칙 1).
 * 주문의 결제수단이 PG일 때만 부른다. 확정 전 주문과 포인트로 결제한 주문에는 결제가 없다(ADR 0028 규칙 1).
 */
interface PaymentFinder {
    /** 주문 [orderId]의 결제. 결제수단이 PG인 주문에 결제가 없는 것은 데이터의 불일치이므로 `PAYMENT_MISSING`이다. */
    fun findByOrder(orderId: Long): Payment

    /**
     * 주문 [orderIds]의 결제를 한 번에 읽는다. 목록이 한 조각에 있는 주문마다 결제를 읽지 않게 한다. [orderIds]가 비면 읽지 않는다.
     * 결제가 없는 주문이 하나라도 있으면 [findByOrder]처럼 `PAYMENT_MISSING`이다. 그 주문만 빼고 돌려주면 목록이 불일치를 감춘다.
     */
    fun findAllByOrders(orderIds: Collection<Long>): List<Payment>
}
