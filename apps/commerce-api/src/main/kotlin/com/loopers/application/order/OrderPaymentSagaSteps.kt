package com.loopers.application.order

import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.order.required.OrderConfirmedEvent
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.payment.provided.PaymentRecorder
import com.loopers.application.payment.provided.PaymentValidator
import com.loopers.application.product.provided.StockReserver
import com.loopers.domain.order.Order
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.ReconcileResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ApplicationService
import java.time.Clock

/**
 * 주문 확정의 두 트랜잭션 단계(ADR 0024 규칙 1). TX A는 결제를 시작하고, TX B는 PG의 답을 결제와 주문에 반영한다. TX B의 입구는
 * PG 거래의 반영과 생성 전 거절의 반영 둘이다(ADR 0028 규칙 3). 대사도 조회로 찾은 PG 거래를 같은 PG 거래 입구로 반영한다(ADR 0024 규칙 5).
 * 둘 사이의 PG 호출은 [OrderPaymentSaga]가 트랜잭션 밖에서 한다. 단계마다 이 빈의 메서드라서 프록시를 지나 선언한 트랜잭션이 걸리고,
 * 장면 테스트가 한 단계만 실패시킬 수 있다(ADR 0028 규칙 2). provided 포트가 아니라 조율하는 Service만 부른다.
 *
 * 두 단계 모두 첫 문장에서 주문을 잠근다. 같은 주문의 확정은 이 잠금에서 차례로 지나가고, 결제를 바꾸는 트랜잭션이 모두 주문을 먼저 잠그므로
 * 결제 행은 잠그지 않는다(ADR 0028 규칙 4). 포인트 계정은 읽지 않는다(ADR 0027).
 */
@ApplicationService
class OrderPaymentSagaSteps(
    private val orderRepository: OrderRepository,
    private val orderEventPublisher: OrderEventPublisher,
    private val orderFinder: OrderFinder,
    private val orderInfoAssembler: OrderInfoAssembler,
    private val stockReserver: StockReserver,
    private val paymentFinder: PaymentFinder,
    private val paymentValidator: PaymentValidator,
    private val paymentRecorder: PaymentRecorder,
    private val clock: Clock,
) {
    /**
     * TX A. 요청자의 주문을 잠그고 이 차례로 본다(ADR 0025 규칙 3).
     *
     * 1. 없거나 남의 주문이면 [OrderFinder.findForUpdate]가 `ORDER_NOT_FOUND`로 거절한다.
     * 2. 결제수단이 PG인 주문, 곧 결제가 있는 주문에 결제를 시작한 키로 온 확정은 재요청이다. 아무것도 바꾸지 않고 저장된 상태로 답하며,
     *    보낼 결제가 없다. 다른 키는 `IDEMPOTENCY_KEY_MISMATCH`다. 같은 주문의 뒤진 확정은 주문 잠금을 기다린 뒤 결제가 있는 주문을 읽고
     *    여기로 온다.
     * 3. 그 밖의 주문은 확정 전이어야 한다. 포인트로 확정된 주문은 [Order.ensureDraft]가 `ORDER_ALREADY_CONFIRMED`로 거절한다. 2와 3의
     *    주문은 겹치지 않으므로 포인트로 확정된 주문을 먼저 보아도 답은 같다.
     * 4. 확정 전 주문에 다른 결제가 쓴 키면 [PaymentValidator]가 `IDEMPOTENCY_KEY_IN_USE`로 거절한다.
     * 5. 재고를 예약한다. 재고 부족과 주문할 수 없는 상품은 결제를 만들기 전에 거절된다. 이 트랜잭션이 되돌아가므로 주문은 확정 전 그대로이고
     *    멱등 키도 쓰이지 않는다.
     * 6. 주문을 결제 중인 주문으로 바꾸고 결제를 결과 미확인으로 만든다. 이 커밋이 발송 기록이다. 결과 미확인 결제를 커밋한 요청만 생성을
     *    보낸다(ADR 0024 규칙 2).
     *
     * 1–4는 상품을 읽기 전이다. 잠금은 주문 → 상품(id 오름차순) → 상품 목록 revision(품절이 바뀌면)의 차례다(ADR 0028 규칙 4). 상품은
     * [StockReserver.reserve]가, revision은 커밋 앞의 훅이 잠근다. 결제는 잠그지 않는다. 결제를 바꾸는 트랜잭션이 모두 주문을 먼저 잠그므로 주문 잠금
     * 뒤의 일반 읽기가 가장 최근에 커밋된 결제를 보고, 새 결제는 새 행으로 들어간다.
     */
    fun startPayment(userId: Long, orderId: Long, idempotencyKey: String): StartedPayment {
        val order = orderFinder.findForUpdate(userId, orderId)

        if (order.paysThroughPg) {
            val payment = paymentFinder.findByOrder(order.id)

            if (!payment.isStartedWith(idempotencyKey)) {
                throw CoreException(ErrorType.IDEMPOTENCY_KEY_MISMATCH)
            }

            return StartedPayment(OrderInfo(order, payment.status), payment = null)
        }

        order.ensureDraft()
        paymentValidator.validateForStart(idempotencyKey)

        reserveStock(order)

        order.startPayment()
        val payment = paymentRecorder.start(orderId = order.id, idempotencyKey = idempotencyKey, amount = order.totalAmount)

        val savedOrder = orderRepository.save(order)

        return StartedPayment(OrderInfo(savedOrder, payment.status), payment)
    }

    /**
     * TX B의 PG 거래 입구. 주문을 다시 잠그고 PG 거래를 결제에 반영한 뒤, 그 결과로 주문을 바꾼다([applyResult]). 확정이 받은 생성의
     * 답과 대사가 찾은 조회의 답이 모두 이리로 온다. 실패한 PG 거래면 거래 키를 남긴 채 결제에 실패한 주문이 된다.
     *
     * 다시 해도 된다(ADR 0024 규칙 5). 이 트랜잭션이 DB 오류로 되돌아가면 결제는 그대로 남고, 대사가 같은 PG 거래로 다시 부른다. 같은 답을
     * 둘이 함께 반영하면 뒤진 쪽은 주문 잠금에서 기다린 뒤 끝난 결제를 읽어 `Unchanged`가 되므로 주문도 예약도 한 번만 바뀐다.
     */
    fun applyTransaction(userId: Long, orderId: Long, transaction: PgTransaction): OrderInfo {
        val order = orderFinder.findForUpdate(userId, orderId)

        val reconcileResult = paymentRecorder.reconcile(orderId = order.id, transaction = transaction)

        return applyResult(order, reconcileResult)
    }

    /**
     * TX B의 거절 입구. 주문을 다시 잠그고 생성 전 거절을 결제에 반영한 뒤, 그 결과로 주문을 바꾼다([applyResult]).
     * 생성 전 거절은 PG 거래가 없다는 증거라서 결제는 거래 키 없이 실패로 끝난다(ADR 0022).
     */
    fun applyRejection(userId: Long, orderId: Long, rejection: PgRejection): OrderInfo {
        val order = orderFinder.findForUpdate(userId, orderId)

        val reconcileResult = paymentRecorder.reject(orderId = order.id, rejection = rejection)

        return applyResult(order, reconcileResult)
    }

    /**
     * TX B의 두 입구가 결제를 바꾼 결과로 잠근 주문을 바꾼다. 주문은 결제 상태가 바뀌어 끝났을 때만 바뀐다(ADR 0024 규칙 1, ADR 0021 규칙 2).
     *
     * - 성공으로 바뀌면 주문을 확정한다. 예약은 더 줄이지 않고 소비한다.
     * - 실패로 바뀌면 결제에 실패한 주문으로 바꾸고 예약을 돌려준다. 돌려주기는 이 바뀜에서만 하므로 한 번뿐이다. 주문 잠금 아래에서 결제 중인 주문이
     *   결제에 실패한 주문으로 가는 것은 한 번이고, 끝난 결제는 같은 답에 `Unchanged`라 다시 실패로 가지 않는다(ADR 0026 규칙 3).
     * - 처리 중, 바뀌지 않음, 충돌이면 주문은 그대로다.
     *
     * A change to success also publishes the confirmation. Once the order is saved, the step hands its [OrderConfirmedEvent]
     * to [OrderEventPublisher] inside this transaction, so the event is delivered only if TX B commits (ADR 0032 rules 1
     * and 7). This is the port's only caller. No other result publishes: a failure or a payment still in progress leaves the
     * order unconfirmed, `Conflicted` changes nothing, and `Unchanged` (a late create answer, or a callback or reconcile that
     * waited on the order lock while another applied the same answer) repeats an answer that an earlier change applied and,
     * if that change confirmed the order, published.
     *
     * 잠금은 주문 → 상품(실패일 때만, id 오름차순) → 상품 목록 revision(품절이 풀리면)의 차례다(ADR 0028 규칙 4). 상품은 [StockReserver.release]가,
     * revision은 커밋 앞의 훅이 잠근다. 결제는 주문 잠금 뒤의 일반 읽기로 가장 최근에 커밋된 것을 본다.
     */
    private fun applyResult(order: Order, result: ReconcileResult): OrderInfo {
        if (result is ReconcileResult.Changed) {
            when (result.to) {
                PaymentStatus.SUCCESS -> order.completePayment(clock.instant())

                PaymentStatus.FAILED -> {
                    order.failPayment()
                    stockReserver.release(order.productQuantities)
                }

                PaymentStatus.UNKNOWN, PaymentStatus.PENDING -> Unit
            }
        }

        val savedOrder = orderRepository.save(order)

        if (result is ReconcileResult.Changed && result.to == PaymentStatus.SUCCESS) {
            orderEventPublisher.publish(OrderConfirmedEvent.from(savedOrder))
        }

        return orderInfoAssembler.toInfo(savedOrder)
    }

    /**
     * 품목의 재고를 상품 슬라이스가 한 번에 예약한다. 예약은 재고에서 미리 빼 두는 것이다(ADR 0026).
     * 상품 행의 잠금과 없는 상품의 거절은 그 슬라이스가 맡으므로 확정은 상품을 읽지 않는다(ADR 0018). 삭제와 재고 부족이 함께면 품목 차례와 무관하게
     * 없는 상품의 거절이 앞선다(설계 15).
     *
     * 상품 슬라이스는 없거나 삭제된 상품을 `PRODUCT_NOT_FOUND`로 거절한다. 주문에게는 주문할 수 없는 상품이므로 생성과 같은
     * `ORDER_PRODUCT_NOT_AVAILABLE`로 옮긴다. 재고 부족은 그대로 지나간다. 상품 슬라이스의 Service도 이 트랜잭션에 참여하므로 그 예외가
     * 나오는 순간 트랜잭션은 되돌릴 것으로 표시된다. 잡은 예외는 삼키지 않고 반드시 다시 던진다.
     */
    private fun reserveStock(order: Order) {
        try {
            stockReserver.reserve(order.productQuantities)
        } catch (e: CoreException) {
            if (e.errorType == ErrorType.PRODUCT_NOT_FOUND) {
                throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
            }

            throw e
        }
    }

    /**
     * TX A의 답. [info]는 저장된 상태이고, [payment]는 PG에 생성을 보낼 결제다. 결제를 시작했으면 결제 중인 주문과 결과 미확인 결제를
     * 커밋했고, 재요청이면 바꾼 것이 없어 [info]는 그때의 주문과 결제 상태, [payment]는 null이다.
     */
    data class StartedPayment(
        val info: OrderInfo,
        val payment: Payment?,
    )
}
