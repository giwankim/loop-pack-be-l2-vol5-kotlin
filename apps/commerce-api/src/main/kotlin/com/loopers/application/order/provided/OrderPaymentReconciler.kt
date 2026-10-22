package com.loopers.application.order.provided

/**
 * 고객의 대사 요청(대사하다). 끝나지 않은 결제를 PG 주문 번호로 조회해, 찾은 PG 거래를 확정의 TX B와 같은 경계로 반영한다(ADR 0024 규칙 5).
 * 확정이 시간 초과, 잃은 응답, 실패한 내부 저장으로 놓친 결과를 PG 거래의 생성을 다시 보내지 않고 되찾는다(ADR 0024 규칙 3).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface OrderPaymentReconciler {
    /**
     * 요청자의 주문 [orderId]의 결제를 대사하고, 주문과 그 결제 상태를 돌려준다.
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
