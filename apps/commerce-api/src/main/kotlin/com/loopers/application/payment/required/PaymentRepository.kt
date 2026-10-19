package com.loopers.application.payment.required

import com.loopers.domain.payment.Payment
import org.springframework.data.repository.Repository

/**
 * 결제 저장소. Spring Data가 구현을 만든다. 주문 식별자와 멱등 키는 각각 유일하므로 어느 쪽으로 찾아도 결제 하나이고,
 * 없으면 null이다.
 */
interface PaymentRepository : Repository<Payment, Long> {
    fun save(payment: Payment): Payment

    fun findByOrderId(orderId: Long): Payment?

    fun findByIdempotencyKey(idempotencyKey: String): Payment?
}
