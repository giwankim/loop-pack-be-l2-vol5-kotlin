package com.loopers.application.payment.provided

/**
 * 결제를 시작하기 전에 보는 사전 조건. 저장소에 물어야 답할 수 있는 것만 여기 있고, 키의 형식과 금액은
 * [com.loopers.domain.payment.Payment]가 지킨다(ADR 0014). 첫 거절에서 던지고 멈춘다.
 */
interface PaymentValidator {
    /**
     * 다른 결제가 [idempotencyKey]를 이미 썼으면 `IDEMPOTENCY_KEY_IN_USE`를 던진다. 키는 대소문자까지 그대로 견주고, 어느 사용자의 결제든
     * 같은 키면 쓴 것이다(ADR 0025 규칙 2). 주문 확정의 TX A가 재고를 예약하기 전에 부르므로 거절된 확정은 아무것도 예약하지 않는다.
     */
    fun validateForStart(idempotencyKey: String)
}
