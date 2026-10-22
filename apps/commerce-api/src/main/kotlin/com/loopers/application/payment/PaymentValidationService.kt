package com.loopers.application.payment

import com.loopers.application.payment.provided.PaymentValidator
import com.loopers.application.payment.required.PaymentRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ApplicationService

/**
 * [PaymentValidator]의 구현. 키가 쓰였는지 자기 저장소에 묻는다. 읽기만 하므로 `readOnly`로 열고, 확정의 TX A 안에서는 거기에 참여한다.
 *
 * 이 조회는 경합을 막지 못한다. 같은 키로 겨루는 두 확정이 함께 지나가면 늦은 쪽의 결제 삽입이 `UK_PAYMENT_ON_IDEMPOTENCY_KEY`에 걸리고,
 * 조율하는 Service가 그것을 같은 거절로 옮긴다(ADR 0025 규칙 3).
 */
@ApplicationService(readOnly = true)
class PaymentValidationService(
    private val paymentRepository: PaymentRepository,
) : PaymentValidator {
    override fun validateForStart(idempotencyKey: String) {
        if (paymentRepository.findByIdempotencyKey(idempotencyKey) != null) {
            throw CoreException(ErrorType.IDEMPOTENCY_KEY_IN_USE)
        }
    }
}
