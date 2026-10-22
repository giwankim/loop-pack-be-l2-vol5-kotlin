package com.loopers.application.order

import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.payment.provided.PaymentReconciler
import com.loopers.application.payment.provided.PaymentStarter
import com.loopers.application.product.provided.StockDeductor
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderAlreadyConfirmedException
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.ReconcileResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ApplicationService

/**
 * 주문 확정의 두 트랜잭션 단계(ADR 0024 규칙 1). TX A는 결제를 시작하고, TX B는 PG의 답을 결제와 주문에 반영한다.
 * 둘 사이의 PG 호출은 [OrderPaymentService]가 트랜잭션 밖에서 한다. 단계마다 이 빈의 메서드라서 프록시를 지나 선언한 트랜잭션이 걸리고,
 * 장면 테스트가 한 단계만 실패시킬 수 있다(ADR 0028 규칙 2). provided 포트가 아니라 조율하는 Service만 부른다.
 *
 * 두 단계 모두 첫 문장에서 주문을 잠근다. 같은 주문의 확정은 이 잠금에서 차례로 지나가고, 결제를 바꾸는 트랜잭션이 모두 주문을 먼저 잠그므로
 * 결제 행은 잠그지 않는다(ADR 0028 규칙 4). 포인트 계정은 읽지 않는다(ADR 0027).
 */
@ApplicationService
class OrderPaymentSteps(
    private val orderRepository: OrderRepository,
    private val orderFinder: OrderFinder,
    private val orderInfoAssembler: OrderInfoAssembler,
    private val stockDeductor: StockDeductor,
    private val paymentStarter: PaymentStarter,
    private val paymentReconciler: PaymentReconciler,
) {
    /**
     * TX A. 요청자의 확정 전 주문을 잠그고, 재고를 예약하고, 주문을 결제 중인 주문으로 바꾸고, 결제를 결과 미확인으로 만든다.
     * 이 커밋이 발송 기록이다. 결과 미확인 결제를 커밋한 요청만 생성을 보낸다(ADR 0024 규칙 2).
     *
     * - 확정 전이 아닌 주문은 상품을 읽기 전에 `ORDER_ALREADY_CONFIRMED`로 거절한다. 같은 주문의 뒤진 확정은 주문 잠금을 기다린 뒤
     *   결제 중인 주문을 읽고 여기서 거절된다.
     * - 재고 부족과 주문할 수 없는 상품은 결제를 만들기 전에 거절된다. 이 트랜잭션이 되돌아가므로 주문은 확정 전 그대로이고 멱등 키도 쓰이지 않는다.
     * - 잠금은 주문 → 상품(id 오름차순) → 상품 목록 revision(품절이 바뀌면)의 차례다(ADR 0028 규칙 4). 상품은 [StockDeductor]가,
     *   revision은 커밋 앞의 훅이 잠근다. 결제는 새 행으로 들어간다.
     */
    fun startPayment(userId: Long, orderId: Long, idempotencyKey: String): StartedPayment {
        val order = orderFinder.findForUpdate(userId, orderId)
        if (order.status != OrderStatus.DRAFT) {
            throw OrderAlreadyConfirmedException()
        }

        reserveStock(order)
        order.startPayment()
        val payment = paymentStarter.start(orderId = order.id, idempotencyKey = idempotencyKey, amount = order.totalAmount)

        val saved = orderRepository.save(order)

        return StartedPayment(OrderInfo(saved, payment.status), payment)
    }

    /**
     * TX B. 주문을 다시 잠그고 받아들여진 PG 거래를 결제에 반영한다. 결제가 성공으로 바뀌었을 때만 주문을 확정하고, 예약은 더 줄이지 않고 소비한다.
     * 처리 중, 바뀌지 않음, 충돌이면 주문은 그대로다(ADR 0024 규칙 1, ADR 0021 규칙 2).
     *
     * 잠금은 주문뿐이다. 결제는 주문 잠금 뒤의 일반 읽기로 가장 최근에 커밋된 것을 본다(ADR 0028 규칙 4).
     */
    fun applyTransaction(userId: Long, orderId: Long, transaction: PgTransaction): OrderInfo {
        val order = orderFinder.findForUpdate(userId, orderId)
        val result = paymentReconciler.reconcile(orderId = order.id, transaction = transaction)
        if (result is ReconcileResult.Changed && result.to == PaymentStatus.SUCCESS) {
            order.completePayment()
        }

        val saved = orderRepository.save(order)

        return orderInfoAssembler.toInfo(saved)
    }

    /**
     * 품목의 재고를 상품 조각이 한 번에 예약한다. 예약은 재고에서 미리 빼 두는 것이라 차감과 같은 포트를 쓴다(ADR 0026).
     * 상품 행의 잠금과 없는 상품의 거절은 그 조각이 맡으므로 확정은 상품을 읽지 않는다(ADR 0018). 삭제와 재고 부족이 함께면 품목 차례와 무관하게
     * 없는 상품의 거절이 앞선다(설계 15).
     *
     * 상품 조각은 없거나 삭제된 상품을 `PRODUCT_NOT_FOUND`로 거절한다. 주문에게는 주문할 수 없는 상품이므로 생성과 같은
     * `ORDER_PRODUCT_NOT_AVAILABLE`로 옮긴다. 재고 부족은 그대로 지나간다. 상품 조각의 Service도 이 트랜잭션에 참여하므로 그 예외가
     * 나오는 순간 트랜잭션은 되돌릴 것으로 표시된다. 잡은 예외는 삼키지 않고 반드시 다시 던진다.
     */
    private fun reserveStock(order: Order) {
        val quantities = order.lineItems.associate { item -> item.productId to item.quantity }

        try {
            stockDeductor.deduct(quantities)
        } catch (e: CoreException) {
            if (e.errorType == ErrorType.PRODUCT_NOT_FOUND) {
                throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
            }
            throw e
        }
    }

    /** TX A가 커밋한 것. [info]는 저장된 상태(결제 중인 주문과 결과 미확인 결제)이고, [payment]는 PG에 생성을 보낼 결제다. */
    data class StartedPayment(
        val info: OrderInfo,
        val payment: Payment,
    )
}
