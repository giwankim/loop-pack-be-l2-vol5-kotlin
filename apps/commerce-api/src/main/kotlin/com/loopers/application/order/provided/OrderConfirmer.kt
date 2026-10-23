package com.loopers.application.order.provided

import jakarta.validation.Valid

/**
 * 고객의 주문 확정(주문을 확정하다). 재고를 예약하고 결제를 하나 만들어 PG에 PG 거래를 요청하고([confirm], ADR 0024), 확정이 놓친 PG의 답을
 * 대사로 되찾는다([reconcile]). 확정은 결제의 성공을 확인할 때 끝나므로 대사는 확정을 마무리하는 쓰기다.
 *
 * 확정과 대사는 유스케이스가 다르지만 포트 하나에 둔다. 구현도, 부르는 쪽(고객)도, 트랜잭션 밖이라는 조건도 같다(ADR 0013).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface OrderConfirmer {
    /**
     * 요청자의 확정 전 주문을 [idempotencyKey]의 결제로 확정하고, 주문과 그 결제 상태를 돌려준다. 결제 금액은 저장된 주문의 합계이고,
     * [request]의 카드는 PG에 넘길 뿐 저장하지 않는다.
     *
     * - 받아들여진 PG 거래가 성공이면 주문은 확정된 주문이다. 처리 중이거나 PG의 답을 모르면 결제 중인 주문으로 남고 예약도 그대로다.
     *   PG의 답을 모를 때는 답하는 때에 저장된 상태를 돌려주므로, 그사이 대사([reconcile])가 결제를 끝냈으면 그 결과다.
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

    /**
     * 요청자의 주문 [orderId]의 결제를 대사하고, 주문과 그 결제 상태를 돌려준다(대사하다). 끝나지 않은 결제를 PG 주문 번호로 조회해, 찾은 PG
     * 거래를 [confirm]의 TX B와 같은 경계로 반영한다(ADR 0024 규칙 5). 확정이 시간 초과, 잃은 응답, 실패한 내부 저장으로 놓친 결과를 PG 거래의
     * 생성을 다시 보내지 않고 되찾는다(ADR 0024 규칙 3).
     *
     * - 결제 중인 주문만 PG에 조회한다. 찾은 PG 거래로 결제가 성공으로 끝나면 주문은 확정된 주문, 실패로 끝나면 결제에 실패한 주문이 되고
     *   예약을 돌려준다. 같은 답을 확정이나 다른 대사가 함께 반영해도 주문은 한 번만 바뀐다.
     * - PG 거래를 찾지 못했거나, 조회할 수 없거나, PG 거래가 여럿이거나 결제와 맞지 않으면 결제와 예약은 그대로이고 저장된 상태를 돌려준다.
     *   기한이 없으므로 몇 번을 물어도 결제를 실패로 끝내지 않는다(ADR 0024 규칙 4).
     * - 포인트로 확정된 주문과 결제가 끝난 주문은 PG에 조회하지 않고 저장된 상태를 돌려준다.
     * - 없는 주문도 남의 주문도 `ORDER_NOT_FOUND`이고, 확정 전 주문은 대사할 결제가 없어 `PAYMENT_NOT_STARTED`다.
     * - 트랜잭션 안에서 부르면 `IllegalTransactionStateException`이다. PG 조회가 트랜잭션에 들지 않게 막는다(ADR 0028 규칙 2).
     */
    fun reconcile(userId: Long, orderId: Long): OrderInfo
}
