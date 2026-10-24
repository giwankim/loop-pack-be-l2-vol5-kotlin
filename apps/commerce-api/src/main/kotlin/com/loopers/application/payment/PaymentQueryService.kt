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

    /** 빈 `in` 목록도 Hibernate는 조회로 보내므로, 결제수단이 PG인 주문이 없는 조각은 여기서 읽지 않고 돌아간다. */
    override fun findAllByOrders(orderIds: Collection<Long>): List<Payment> {
        if (orderIds.isEmpty()) {
            return emptyList()
        }

        val payments = paymentRepository.findAllByOrderIdIn(orderIds)
        if (!payments.map { it.orderId }.containsAll(orderIds)) {
            throw CoreException(ErrorType.PAYMENT_MISSING)
        }

        return payments
    }

    override fun findByPgOrderId(pgOrderId: String): Payment {
        return paymentRepository.findByPgOrderId(pgOrderId) ?: throw CoreException(ErrorType.PAYMENT_NOT_FOUND)
    }
}
