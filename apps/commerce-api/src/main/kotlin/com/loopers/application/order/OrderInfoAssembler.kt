package com.loopers.application.order

import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.domain.order.Order
import org.springframework.data.domain.Slice
import org.springframework.stereotype.Component

/**
 * 결제를 읽어 [OrderInfo]를 만드는 자리. 주문 상세, 주문 목록, 확정과 대사의 TX B, 대사가 PG를 부르지 않는 끝난 주문이 같은 규칙으로 결제
 * 상태를 붙인다. 결제수단이 PG인 주문만 결제를 읽는다. 확정 전 주문과 포인트로 결제한 주문에는 결제가 없다(ADR 0028 규칙 1).
 *
 * 결제를 이미 손에 쥔 TX A와 갓 만든 확정 전 주문의 응답은 결제를 다시 읽지 않고 [OrderInfo]를 바로 만든다. 결제 상태가 어느 주문에 있는지는
 * [OrderInfo]가 만들 때 지킨다.
 */
@Component
class OrderInfoAssembler(
    private val paymentFinder: PaymentFinder,
) {
    fun toInfo(order: Order): OrderInfo {
        if (!order.paysThroughPg) {
            return OrderInfo(order, paymentStatus = null)
        }

        val payment = paymentFinder.findByOrder(order.id)

        return OrderInfo(order, payment.status)
    }

    /** 목록의 한 조각. 결제수단이 PG인 주문의 결제를 한 번에 읽는다. 주문마다 읽으면 조각 크기만큼 조회가 는다. */
    fun toInfos(orders: Slice<Order>): Slice<OrderInfo> {
        val orderIdsWithPayment = orders.content.filter(Order::paysThroughPg).map { it.id }
        val paymentStatuses = paymentFinder.findAllByOrders(orderIdsWithPayment).associate { it.orderId to it.status }

        return orders.map { order -> OrderInfo(order, paymentStatuses[order.id]) }
    }
}
