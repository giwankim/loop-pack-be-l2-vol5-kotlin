package com.loopers.application.order.provided

import com.loopers.domain.order.Order
import com.loopers.domain.payment.PaymentStatus

/**
 * 주문과 그 결제 상태. 결제는 다른 애그리거트라 애그리거트를 건너는 Info로 돌려준다(ADR 0014). 주문 슬라이스는 결제에서 결제 상태만 읽어
 * 옮기고, 거래 키, PG 주문 번호, 카드는 담지 않는다(ADR 0028 규칙 5). 결제수단은 [order]가 가진다.
 *
 * [paymentStatus]는 결제수단이 PG인 주문에만 있다. 확정 전 주문과 포인트로 결제한 주문에는 결제가 없어 null이다. 이 규칙은 Info를 만드는
 * 곳마다 다시 적지 않고 여기서 지킨다. 어긴 Info는 결제수단과 결제 상태가 맞지 않는 응답을 실으므로 만들 때 거절한다.
 */
data class OrderInfo(
    val order: Order,
    val paymentStatus: PaymentStatus?,
) {
    init {
        require(order.paysThroughPg == (paymentStatus != null)) {
            "결제 상태는 결제수단이 PG인 주문에만 있다. orderId=${order.id}, paymentMethod=${order.paymentMethod}, paymentStatus=$paymentStatus"
        }
    }
}
