package com.loopers.application.order.provided

import jakarta.validation.Valid

/**
 * 고객의 주문 확정(주문을 확정하다). 재고를 예약하고 결제를 하나 만들어 PG에 PG 거래를 요청한다(ADR 0024).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface OrderConfirmer {
    /**
     * 요청자의 확정 전 주문을 [idempotencyKey]의 결제로 확정하고, 주문과 그 결제 상태를 돌려준다. 결제 금액은 저장된 주문의 합계이고,
     * [request]의 카드는 PG에 넘길 뿐 저장하지 않는다.
     *
     * - 받아들여진 PG 거래가 성공이면 주문은 확정된 주문이다. 처리 중이거나 PG의 답을 모르면 결제 중인 주문으로 남고 예약도 그대로다.
     * - 결제가 있는 주문에 그 결제를 시작한 키로 다시 확정하면 재요청이다. PG를 부르지도 조회하지도 않고 지금 저장된 상태를 돌려준다. 카드는
     *   견주지 않는다(ADR 0025 규칙 3).
     * - 아래 차례로 거절한다. 없는 주문도 남의 주문도 `ORDER_NOT_FOUND`다. 포인트로 확정된 주문은 `ORDER_ALREADY_CONFIRMED`, 결제가 있는 주문에
     *   다른 키는 `IDEMPOTENCY_KEY_MISMATCH`, 다른 주문이 쓴 키는 남의 주문의 것이어도 `IDEMPOTENCY_KEY_IN_USE`다. 이 거절들은 아무것도
     *   바꾸지 않는다.
     * - 재고 부족과 주문할 수 없는 상품은 PG를 부르기 전에 거절되고 주문은 확정 전 그대로다. 주문할 수 없는 상품이 재고 부족보다 앞선다(설계 15).
     *   결제가 생기지 않았으므로 키도 쓰이지 않아 같은 키로 다시 확정할 수 있다.
     * - 트랜잭션 안에서 부르면 `IllegalTransactionStateException`이다. PG 호출이 트랜잭션에 들지 않게 막는다(ADR 0028 규칙 2).
     */
    fun confirm(userId: Long, orderId: Long, idempotencyKey: String, @Valid request: OrderConfirmRequest): OrderInfo
}
