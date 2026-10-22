package com.loopers.application.payment

import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.payment.required.PaymentRepository
import com.loopers.domain.payment.Payment
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ApplicationService

/** [PaymentFinder]의 구현. 결제는 Spring Data의 [PaymentRepository]가 읽는다. */
@ApplicationService(readOnly = true)
class PaymentQueryService(
    private val paymentRepository: PaymentRepository,
) : PaymentFinder {
    override fun findByOrder(orderId: Long): Payment {
        return paymentRepository.findByOrderId(orderId) ?: throw CoreException(ErrorType.PAYMENT_MISSING)
    }
}
