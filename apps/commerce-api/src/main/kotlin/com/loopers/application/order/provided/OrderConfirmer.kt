package com.loopers.application.order.provided

/**
 * 고객의 주문 확정(주문을 확정하다). 재고와 포인트를 함께 차감해 결제를 마친다.
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface OrderConfirmer {
    /**
     * 요청자의 확정 전 주문을 확정하고 확정된 주문을 돌려준다. 재고·잔액·확정 상태를 함께 커밋하며,
     * 실패는 확정 전 주문을 그대로 남긴다(ADR 0003). 결제의 기록은 확정된 주문이다(ADR 0006).
     *
     * 없는 주문도 남의 주문도 `ORDER_NOT_FOUND`다. 이미 확정된 주문은 차감 없이 거절한다(ADR 0005).
     * 품목의 상품을 주문할 수 없으면 재고 부족보다 앞서 `ORDER_PRODUCT_NOT_AVAILABLE`을 던진다(설계 15).
     */
    fun confirm(userId: Long, orderId: Long): OrderInfo
}
