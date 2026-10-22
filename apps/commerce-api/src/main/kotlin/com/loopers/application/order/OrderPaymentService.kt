package com.loopers.application.order

import com.loopers.application.order.OrderPaymentSteps.StartedPayment
import com.loopers.application.order.provided.OrderConfirmRequest
import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.payment.provided.PaymentValidator
import com.loopers.application.payment.provided.PgOutcomeUnknownException
import com.loopers.application.payment.provided.PgRejectedException
import com.loopers.application.payment.provided.PgTransactionCreator
import com.loopers.domain.payment.CreditCard
import com.loopers.support.stereotype.ValidatedApplicationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Propagation

private val log = KotlinLogging.logger {}

/**
 * [OrderConfirmer]의 구현. 주문 확정이 PG로 결제하는 절차를 조율한다(ADR 0024, ADR 0028 규칙 1). 결제 조각은 주문을 모르므로
 * 이 절차의 주인인 주문 조각이 조율한다. 확정 요청이 카드를 담으므로 검증하는 Service다.
 *
 * `NEVER`라서 트랜잭션 안에서 불리면 Spring이 `IllegalTransactionStateException`으로 거절한다. PG 호출이 트랜잭션에 드는 실수를
 * KDoc 대신 실행이 막는다(ADR 0028 규칙 2). TX A와 TX B는 [OrderPaymentSteps]의 메서드로 각자 커밋하고, 그 사이의 PG 호출은 어느
 * 트랜잭션에도 들지 않는다. 주문 조각은 카드와 PG 거래를 결제 조각의 부름 사이에서 옮길 뿐 읽지 않는다(ADR 0028 규칙 5).
 */
@ValidatedApplicationService(propagation = Propagation.NEVER)
class OrderPaymentService(
    private val orderPaymentSteps: OrderPaymentSteps,
    private val paymentValidator: PaymentValidator,
    private val pgTransactionCreator: PgTransactionCreator,
) : OrderConfirmer {
    /**
     * 같은 키의 재요청이면 TX A가 보낼 결제 없이 저장된 상태로 답하므로, 생성도 조회도 하지 않고 그대로 돌려준다(ADR 0024 규칙 3,
     * ADR 0025 규칙 3).
     *
     * 생성의 답은 타입마다 갈린다(ADR 0028 규칙 3). 받아들여진 PG 거래는 TX B의 PG 거래 입구로 간다.
     *
     * - 생성 전 거절(`PgRejectedException`)은 PG 거래가 없다는 증거라서(ADR 0022) TX B의 거절 입구로 간다. 결제는 실패, 주문은 결제에 실패한
     *   주문이 되고 예약을 돌려준다.
     * - PG의 답을 모르면(`PgOutcomeUnknownException`) TX A가 저장한 상태, 곧 결제 중인 주문과 결과 미확인 결제로 답한다. 실패가 아니며
     *   예약은 그대로 남고, 생성은 다시 보내지 않는다(ADR 0024 규칙 3, 4). 결과는 대사가 조회로 안다. 그런 결제를 찾는 지표가 없으므로
     *   WARN을 한 줄 남긴다.
     *
     * 두 예외는 트랜잭션 밖에서 정확한 타입으로 잡는다. 넓게 잡으면 결과 미확인이 실패로 바뀔 수 있다.
     */
    override fun confirm(userId: Long, orderId: Long, idempotencyKey: String, request: OrderConfirmRequest): OrderInfo {
        val started = startPaymentMappingKeyRace(userId, orderId, idempotencyKey)
        val payment = started.payment ?: return started.info
        val card = CreditCard(type = request.cardType, number = request.cardNo)

        val transaction = try {
            pgTransactionCreator.create(payment, card)
        } catch (e: PgRejectedException) {
            return orderPaymentSteps.applyRejection(userId, orderId, e.rejection)
        } catch (e: PgOutcomeUnknownException) {
            log.warn { "PG 거래의 생성 결과를 몰라 결제를 결과 미확인으로 둔다. orderId=$orderId, cause=${e.message}" }
            return started.info
        }

        return orderPaymentSteps.applyTransaction(userId, orderId, transaction)
    }

    /**
     * TX A. 같은 키로 겨룬 두 확정 전 주문이 함께 키의 사전 조건을 지나면, 늦은 쪽의 결제 삽입이 `UK_PAYMENT_ON_IDEMPOTENCY_KEY`에 걸려
     * TX A가 예약과 함께 되돌아간다. MySQL은 앞선 쪽이 커밋한 뒤에야 그 위반을 알리므로, 트랜잭션 밖에서 키를 다시 보면 앞선 쪽의 결제가 보여
     * 사전 조건과 같은 `IDEMPOTENCY_KEY_IN_USE`로 거절된다(ADR 0025 규칙 3). 키가 여전히 쓰이지 않았으면 다른 제약의 위반이라 그대로 던진다.
     *
     * 제약의 이름을 위반에서 읽지 않고 키를 다시 묻는다. 이름은 Hibernate의 예외와 MySQL의 메시지 꼴에 기댄다. 다른 제약의 위반과 같은 키의 커밋이
     * 겹치면 그 위반도 `IDEMPOTENCY_KEY_IN_USE`가 되지만, 그 키는 이미 쓰였으므로 다시 보내도 같은 답이다.
     */
    private fun startPaymentMappingKeyRace(userId: Long, orderId: Long, idempotencyKey: String): StartedPayment {
        try {
            return orderPaymentSteps.startPayment(userId, orderId, idempotencyKey)
        } catch (e: DataIntegrityViolationException) {
            paymentValidator.validateForStart(idempotencyKey)
            throw e
        }
    }
}
