package com.loopers.application.payment

import com.loopers.application.payment.provided.PgTransactionClient
import com.loopers.application.payment.required.PaymentGateway
import com.loopers.application.payment.required.PgTransactionCreateRequest
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PgTransaction
import com.loopers.support.stereotype.ApplicationService
import org.springframework.transaction.annotation.Propagation

/**
 * [PgTransactionClient]의 구현. `NEVER`라서 트랜잭션 안에서 불리면 Spring이 `IllegalTransactionStateException`으로
 * 거절하고 PG에 보내지 않는다. 그래서 결과 미확인이나 조회 불가의 예외가 결제를 저장한 트랜잭션을 지나 그 저장을 되돌리는 일이 없다
 * (ADR 0023 규칙 4, ADR 0028 규칙 2). PG의 예외는 그대로 던진다. 어떻게 다룰지는 조율하는 주문 슬라이스가 예외의 타입마다 정한다(ADR 0028 규칙 3).
 */
@ApplicationService(propagation = Propagation.NEVER)
class PgTransactionService(
    private val paymentGateway: PaymentGateway,
) : PgTransactionClient {
    /** 결제의 금액([com.loopers.domain.shared.Money])을 PG의 원화 정수로 옮긴다. 그 범위를 넘는 결제는 [PaymentModifyService.start]가 만들지 않는다. */
    override fun create(payment: Payment, card: CreditCard): PgTransaction {
        val request = PgTransactionCreateRequest(
            pgOrderId = payment.pgOrderId,
            amount = payment.amount.amount.longValueExact(),
            cardType = card.type,
            cardNumber = card.number,
        )

        return paymentGateway.createTransaction(request)
    }

    override fun retrieve(payment: Payment): PgTransaction? {
        return paymentGateway.retrieveTransactionByPgOrderId(payment.pgOrderId)
    }
}
