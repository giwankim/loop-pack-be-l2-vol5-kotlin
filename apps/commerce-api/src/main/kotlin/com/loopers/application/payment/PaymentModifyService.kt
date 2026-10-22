package com.loopers.application.payment

import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.payment.provided.PaymentReconciler
import com.loopers.application.payment.provided.PaymentStarter
import com.loopers.application.payment.required.PaymentRepository
import com.loopers.domain.payment.InvalidPaymentException
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PgOrderIdGenerator
import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.ReconcileResult
import com.loopers.domain.shared.Money
import com.loopers.support.stereotype.ApplicationService
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * [PaymentStarter]와 [PaymentReconciler]의 구현. 둘 다 부르는 쪽의 트랜잭션(TX A, TX B)에 참여한다(ADR 0028 규칙 1).
 * 주문은 모르고 식별자로만 가리킨다. 바꿀 결제는 같은 조각의 [PaymentFinder]로 얻는다.
 */
@ApplicationService
class PaymentModifyService(
    private val paymentRepository: PaymentRepository,
    private val paymentFinder: PaymentFinder,
    private val pgOrderIdGenerator: PgOrderIdGenerator,
) : PaymentStarter,
    PaymentReconciler {
    /**
     * PG는 금액을 64비트 정수로 받으므로(`PgTransactionCreateRequest.amount`) 그보다 큰 결제는 만들지 않는다. 주문의 합계는 그보다 클 수 있다.
     * 재고를 예약하는 TX A 안에서 거절해야 예약과 함께 되돌아간다. TX A가 커밋한 뒤 PG 요청을 만들다 실패하면 보내지 않을 결제가 결과 미확인으로
     * 남아 예약을 영영 쥔다(ADR 0024 규칙 1). 결제의 금액 열은 그보다 넓은 `DECIMAL(65,0)`이라 이 한도는 PG를 아는 이 조각이 지킨다.
     */
    override fun start(orderId: Long, idempotencyKey: String, amount: Money): Payment {
        if (amount > PG_MAX_AMOUNT) {
            throw InvalidPaymentException("결제 금액은 ${PG_MAX_AMOUNT.amount}원 이하여야 합니다.")
        }

        val payment = Payment.start(
            orderId = orderId,
            idempotencyKey = idempotencyKey,
            amount = amount,
            pgOrderIdGenerator = pgOrderIdGenerator,
        )

        return paymentRepository.save(payment)
    }

    /** 충돌을 남기는 WARN에는 무엇이 어긋났는지 알 만큼만 싣는다. 카드 번호는 여기 오지 않는다. */
    override fun reconcile(orderId: Long, transaction: PgTransaction): ReconcileResult {
        val payment = paymentFinder.findByOrder(orderId)
        val result = payment.reconcile(transaction)
        if (result is ReconcileResult.Conflicted) {
            log.warn {
                "PG 거래가 결제와 맞지 않아 반영하지 않았다. paymentId=${payment.id}, reason=${result.reason}, " +
                    "transactionKey=${transaction.transactionKey}, pgOrderId=${transaction.pgOrderId}"
            }
        }

        paymentRepository.save(payment)

        return result
    }

    /** 생성 전 거절에는 PG 거래가 없으므로 WARN에 거래 키가 없다. 맞지 않는 것은 PG가 되돌려 준 PG 주문 번호나 금액이다. */
    override fun reject(orderId: Long, rejection: PgRejection): ReconcileResult {
        val payment = paymentFinder.findByOrder(orderId)
        val result = payment.reject(rejection)
        if (result is ReconcileResult.Conflicted) {
            log.warn {
                "생성 전 거절이 결제와 맞지 않아 반영하지 않았다. paymentId=${payment.id}, reason=${result.reason}, " +
                    "pgOrderId=${rejection.pgOrderId}"
            }
        }

        paymentRepository.save(payment)

        return result
    }

    companion object {
        private val PG_MAX_AMOUNT = Money(Long.MAX_VALUE)
    }
}
