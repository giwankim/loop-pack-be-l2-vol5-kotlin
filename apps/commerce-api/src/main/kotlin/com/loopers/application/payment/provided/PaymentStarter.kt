package com.loopers.application.payment.provided

import com.loopers.domain.payment.Payment
import com.loopers.domain.shared.Money

/**
 * 주문 확정의 TX A가 부르는 결제 만들기(ADR 0028 규칙 1). 부르는 쪽의 트랜잭션에 참여하므로 결제는 재고 예약, 결제 중인 주문과 함께
 * 커밋되고 함께 되돌아간다. 그 커밋이 발송 기록이다(ADR 0024 규칙 2).
 */
interface PaymentStarter {
    /**
     * 주문 [orderId]의 결제를 결과 미확인으로 만들어 저장하고 돌려준다. PG 주문 번호는 결제가 생성기로 새로 만든다(ADR 0029).
     * 멱등 키나 금액이 결제의 규칙에 맞지 않거나, 금액이 PG가 받는 64비트 정수의 범위를 넘으면
     * [com.loopers.domain.payment.InvalidPaymentException]을 던진다. 부르는 쪽의 트랜잭션이 예약과 함께 되돌아간다.
     *
     * 키가 쓰였는지는 묻지 않는다. 부르는 쪽이 먼저 [PaymentValidator.validateForStart]로 본다. 그 뒤에 다른 결제가 같은 키를 커밋했으면
     * 삽입이 `UK_PAYMENT_ON_IDEMPOTENCY_KEY`에 걸려 `DataIntegrityViolationException`이 난다(ADR 0025 규칙 3).
     */
    fun start(orderId: Long, idempotencyKey: String, amount: Money): Payment
}
