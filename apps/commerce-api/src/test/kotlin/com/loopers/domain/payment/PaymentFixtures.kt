package com.loopers.domain.payment

import com.loopers.domain.shared.Money
import org.instancio.kotlin.KInstancio.gen

/**
 * 저장하지 않은 결제. 진짜 생성자를 불러 결과 미확인 시작과 PG 주문 번호를 생성자에 맡긴다(ADR 0010). 주문 식별자는 다른
 * 애그리거트라 테스트가 넘긴다. 멱등 키는 UUID 문자열(36자)이라 따로 만든 결제끼리 유일 키에 걸리지 않고, 금액은
 * 1..1,000,000,000원이다. 저장할 결제는 주문 외래 키에 걸리므로 저장된 주문의 식별자를 받는다.
 */
fun createPayment(orderId: Long, idempotencyKey: String? = null, amount: Money? = null): Payment {
    return Payment(
        orderId = orderId,
        idempotencyKey = idempotencyKey ?: gen().text().uuid().get(),
        amount = amount ?: Money(gen().longs().range(1, 1_000_000_000).get()),
    )
}

/**
 * [payment]에 맞는 PG 거래. PG 주문 번호와 금액은 그 결제의 것이다. 거래 키는 UUID 문자열이라 따로 만든 PG 거래끼리 겹치지 않고,
 * PG 상태는 셋 가운데 하나를 뽑는다. 결제와 맞지 않는 PG 거래는 테스트가 그 값을 넘겨 만든다.
 */
fun createPgTransaction(
    payment: Payment,
    transactionKey: String? = null,
    pgOrderId: String? = null,
    amount: Money? = null,
    status: PgStatus? = null,
): PgTransaction {
    return PgTransaction(
        transactionKey = transactionKey ?: gen().text().uuid().get(),
        pgOrderId = pgOrderId ?: payment.pgOrderId,
        amount = amount ?: payment.amount,
        status = status ?: gen().enumOf(PgStatus::class.java).get(),
    )
}
