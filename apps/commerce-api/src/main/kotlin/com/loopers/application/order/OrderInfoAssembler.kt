package com.loopers.application.order

import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.domain.order.Order
import com.loopers.domain.order.PaymentMethod
import org.springframework.stereotype.Component

/**
 * [OrderInfo]를 만드는 자리. 주문 상세와 확정의 TX B가 같은 규칙으로 결제 상태를 붙인다.
 * 결제수단이 PG인 주문만 결제를 읽는다. 확정 전 주문과 포인트로 결제한 주문에는 결제가 없다(ADR 0028 규칙 1).
 */
@Component
class OrderInfoAssembler(
    private val paymentFinder: PaymentFinder,
) {
    fun toInfo(order: Order): OrderInfo {
        if (order.paymentMethod != PaymentMethod.PG) {
            return OrderInfo(order, paymentStatus = null)
        }

        return OrderInfo(order, paymentFinder.findByOrder(order.id).status)
    }
}
