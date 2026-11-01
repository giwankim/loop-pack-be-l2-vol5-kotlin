package com.loopers.application.order

import com.loopers.application.order.OrderPaymentSagaSteps.StartedPayment
import com.loopers.application.order.provided.OrderConfirmRequest
import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.order.provided.OrderPaymentReconciler
import com.loopers.application.payment.provided.MultiplePgTransactionsException
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.payment.provided.PaymentValidator
import com.loopers.application.payment.provided.PgOutcomeUnknownException
import com.loopers.application.payment.provided.PgRejectedException
import com.loopers.application.payment.provided.PgTransactionClient
import com.loopers.application.payment.provided.PgUnavailableException
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PgTransaction
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Propagation

private val log = KotlinLogging.logger {}

/**
 * [OrderConfirmer]와 [OrderPaymentReconciler]의 구현. 주문 확정이 PG로 결제하는 절차와, 그 결과를 대사로 되찾는 절차를 조율한다(ADR 0024,
 * ADR 0028 규칙 1). 대사는 고객이 부르는 것과 PG의 알림이 부르는 것 둘이고, 같은 조회와 같은 TX B를 쓴다(ADR 0031 규칙 3). 결제 슬라이스는
 * 주문을 모르므로 이 절차의 주인인 주문 슬라이스가 조율한다. 확정 요청이 카드를 담으므로 검증하는 Service다.
 *
 * In Richardson's saga terms TX A is the compensatable step (a failed payment releases the stock reservation), the PG create is
 * the pivot, and TX B is the retriable step. The saga itself holds no state. The state lives in the order and payment rows.
 *
 * `NEVER`라서 트랜잭션 안에서 불리면 Spring이 `IllegalTransactionStateException`으로 거절한다. PG 호출이 트랜잭션에 드는 실수를
 * KDoc 대신 실행이 막는다(ADR 0028 규칙 2). TX A와 TX B는 [OrderPaymentSagaSteps]의 메서드로 각자 커밋하고, 그 사이의 PG 호출은 어느
 * 트랜잭션에도 들지 않는다. 대사는 확정과 같은 TX B로 반영한다. 주문 슬라이스는 카드와 PG 거래를 결제 슬라이스의 부름 사이에서 옮길 뿐 읽지
 * 않는다(ADR 0028 규칙 5).
 *
 * 고객에게는 PG의 답이 주문을 바꾸지 못한 길(생성의 결과 미확인, 조회의 PG 거래 없음, 조회 불가, PG 거래 여럿)을 저장된 상태로 답한다. 그
 * 상태는 PG를 부르기 전에 읽은 것이 아니라 답하는 때에 다시 읽은 것이다. PG 호출 동안 다른 대사가 결제를 끝냈으면 그 결과로 답한다. PG의
 * 대사는 답할 주문 정보가 없어 다시 읽지 않는다.
 */
@ValidatedApplicationService(propagation = Propagation.NEVER)
class OrderPaymentSaga(
    private val orderPaymentSagaSteps: OrderPaymentSagaSteps,
    private val orderFinder: OrderFinder,
    private val orderInfoAssembler: OrderInfoAssembler,
    private val paymentFinder: PaymentFinder,
    private val paymentValidator: PaymentValidator,
    private val pgTransactionClient: PgTransactionClient,
) : OrderConfirmer, OrderPaymentReconciler {
    /**
     * 키의 형식은 TX A를 열기 전에 본다. 웹 경계도 보지만 이 포트는 웹을 거치지 않고도 불린다(카탈로그 설계 5.25). `utf8mb4_bin`은 PAD
     * SPACE라서, 형식을 보지 않은 키는 뒤 공백만 다른 키의 결제를 찾아 형식 오류 대신 `IDEMPOTENCY_KEY_IN_USE`로 거절된다.
     *
     * 같은 키의 재요청이면 TX A가 보낼 결제 없이 저장된 상태로 답하므로, 생성도 조회도 하지 않고 그대로 돌려준다(ADR 0024 규칙 3,
     * ADR 0025 규칙 3).
     *
     * 생성의 답은 타입마다 갈린다(ADR 0028 규칙 3). 받아들여진 PG 거래는 TX B의 PG 거래 입구로 간다.
     *
     * - 생성 전 거절(`PgRejectedException`)은 PG 거래가 없다는 증거라서(ADR 0022) TX B의 거절 입구로 간다. 결제는 실패, 주문은 결제에 실패한
     *   주문이 되고 예약을 돌려준다.
     * - PG의 답을 모르면(`PgOutcomeUnknownException`) 저장된 상태를 다시 읽어 답한다. 대개 TX A가 저장한 결제 중인 주문과 결과 미확인
     *   결제이고, PG 호출 동안 대사가 결제를 끝냈으면 그 결과다. 실패가 아니며 예약은 그대로 남고, 생성은 다시 보내지 않는다(ADR 0024 규칙
     *   3, 4). 결과는 대사가 조회로 안다. 그런 결제를 찾는 지표가 없으므로 WARN을 한 줄 남긴다.
     *
     * 두 예외는 트랜잭션 밖에서 정확한 타입으로 잡는다. 넓게 잡으면 결과 미확인이 실패로 바뀔 수 있다.
     */
    override fun confirm(userId: Long, orderId: Long, idempotencyKey: String, request: OrderConfirmRequest): OrderInfo {
        Payment.checkIdempotencyKey(idempotencyKey)

        val started = startPaymentOrRejectUsedKey(userId, orderId, idempotencyKey)
        val payment = started.payment ?: return started.info

        val pgTransaction = try {
            pgTransactionClient.create(payment, request.toCreditCard())
        } catch (e: PgRejectedException) {
            return orderPaymentSagaSteps.applyRejection(userId, orderId, e.rejection)
        } catch (e: PgOutcomeUnknownException) {
            log.warn { "PG 거래의 생성 결과를 몰라 결제를 결과 미확인으로 둔다. orderId=$orderId, cause=${e.message}" }
            return orderFinder.findInfo(userId, orderId)
        }

        return orderPaymentSagaSteps.applyTransaction(userId, orderId, pgTransaction)
    }

    /**
     * 주문을 잠그지 않고 읽어 소유와 상태를 본다. 결제 중인 주문만 결제의 PG 주문 번호로 PG에 조회한다([checkLookupNeeded], [retrieveTransaction]).
     *
     * 찾은 PG 거래는 확정과 같은 TX B의 PG 거래 입구로 간다. TX B는 주문을 잠근 뒤 결제를 다시 읽으므로, 확정의 TX B나 다른 대사가 같은 답을
     * 먼저 반영했으면 `Unchanged`라 주문도 예약도 다시 바뀌지 않는다(ADR 0024 규칙 5). 결제와 맞지 않는 PG 거래는 TX B 안의 `Conflicted`가
     * WARN으로 남긴다.
     *
     * - PG 거래가 없으면(`null`) 저장된 상태로 답한다. 0건은 PG 거래가 없다는 증거가 아니다(ADR 0022 규칙 3).
     * - 조회할 수 없으면(`PgUnavailableException`) 저장된 상태로 답한다.
     * - PG 거래가 여럿이면 [retrieveTransaction]이 WARN을 남기고 `null`을 돌려주므로 저장된 상태로 답한다.
     *
     * 세 길 모두 결제와 예약을 그대로 둔다. 기한이 없으므로 몇 번을 물어도 결제를 실패로 끝내지 않는다(ADR 0024 규칙 4). 조회의 예외는 트랜잭션
     * 밖에서 정확한 타입으로 잡는다.
     */
    override fun reconcile(userId: Long, orderId: Long): OrderInfo {
        val order = orderFinder.find(userId, orderId)

        if (!checkLookupNeeded(order)) {
            return orderInfoAssembler.toInfo(order)
        }

        val pgTransaction = try {
            retrieveTransaction(order)
        } catch (_: PgUnavailableException) {
            null
        }

        if (pgTransaction == null) {
            return orderFinder.findInfo(userId, orderId)
        }

        return orderPaymentSagaSteps.applyTransaction(userId, orderId, pgTransaction)
    }

    /**
     * 고객의 [reconcile]과 같은 차례다(ADR 0031 규칙 3). 주문을 사용자 없이 읽고, 찾은 PG 거래는 같은 TX B로 반영한다. TX B는 사용자를 받으므로
     * 읽은 주문의 사용자를 넘긴다. 웹 경계의 요청자가 아니라 주문 슬라이스가 자기 주문에서 읽은 값이다.
     *
     * 다른 것은 둘이다. PG에 돌려줄 주문 정보가 없어 답하는 때에 다시 읽지 않고, 조회할 수 없으면 저장된 상태 대신 `PG_UNAVAILABLE`을 던진다.
     * PG 거래가 없거나 여럿인 것은 대사의 답이라 그대로 돌아간다(ADR 0031 규칙 6).
     */
    override fun reconcile(orderId: Long) {
        val order = orderFinder.findForPg(orderId)

        if (!checkLookupNeeded(order)) {
            return
        }

        val pgTransaction = try {
            retrieveTransaction(order)
        } catch (_: PgUnavailableException) {
            throw CoreException(ErrorType.PG_UNAVAILABLE)
        }

        if (pgTransaction == null) {
            return
        }

        orderPaymentSagaSteps.applyTransaction(order.userId, orderId, pgTransaction)
    }

    /**
     * 두 대사가 PG에 물을 주문인지 본다. 결제 중인 주문만 묻는다. 확정 전 주문은 대사할 결제가 없어 `PAYMENT_NOT_STARTED`로 거절하고, 포인트로
     * 확정된 주문과 결제가 끝난 주문은 묻지 않는다. 거절할 수 있어서 이름이 Kotlin의 `check`처럼 `check`로 시작한다.
     */
    private fun checkLookupNeeded(order: Order): Boolean {
        return when (order.status) {
            OrderStatus.DRAFT -> throw CoreException(ErrorType.PAYMENT_NOT_STARTED)
            OrderStatus.CONFIRMED, OrderStatus.PAYMENT_FAILED -> false
            OrderStatus.PAYMENT_PENDING -> true
        }
    }

    /**
     * [order]의 결제를 PG 주문 번호로 한 번 조회한다. 조회의 답은 타입마다 갈린다(ADR 0028 규칙 3). PG 거래가 여럿이면
     * (`MultiplePgTransactionsException`) 어느 것이 결제의 것인지 모르므로 고르지 않고, WARN을 한 줄 남긴 뒤 PG 거래가 없을 때처럼 `null`을
     * 돌려준다. 조회 불가(`PgUnavailableException`)는 두 대사가 달리 다루므로 그대로 던진다.
     */
    private fun retrieveTransaction(order: Order): PgTransaction? {
        val payment = paymentFinder.findByOrder(order.id)

        try {
            return pgTransactionClient.retrieve(payment)
        } catch (e: MultiplePgTransactionsException) {
            log.warn { "PG가 PG 주문 번호 하나에 PG 거래를 여럿 말해 어느 것도 반영하지 않았다. orderId=${order.id}, cause=${e.message}" }
            return null
        }
    }

    /**
     * TX A. 같은 키로 겨룬 두 확정 전 주문이 함께 키의 사전 조건을 지나면, 늦은 쪽의 결제 삽입이 `UK_PAYMENT_ON_IDEMPOTENCY_KEY`에 걸려
     * TX A가 예약과 함께 되돌아간다. MySQL은 앞선 쪽이 커밋한 뒤에야 그 위반을 알리므로, 트랜잭션 밖에서 키를 다시 보면 앞선 쪽의 결제가 보여
     * 사전 조건과 같은 `IDEMPOTENCY_KEY_IN_USE`로 거절된다(ADR 0025 규칙 3). 키가 여전히 쓰이지 않았으면 다른 제약의 위반이라 그대로 던진다.
     *
     * 제약의 이름을 위반에서 읽지 않고 키를 다시 묻는다. 이름은 Hibernate의 예외와 MySQL의 메시지 꼴에 기댄다. 다른 제약의 위반과 같은 키의 커밋이
     * 겹치면 그 위반도 `IDEMPOTENCY_KEY_IN_USE`가 되지만, 그 키는 이미 쓰였으므로 다시 보내도 같은 답이다.
     */
    private fun startPaymentOrRejectUsedKey(userId: Long, orderId: Long, idempotencyKey: String): StartedPayment {
        try {
            return orderPaymentSagaSteps.startPayment(userId, orderId, idempotencyKey)
        } catch (e: DataIntegrityViolationException) {
            paymentValidator.validateForStart(idempotencyKey)
            throw e
        }
    }
}
