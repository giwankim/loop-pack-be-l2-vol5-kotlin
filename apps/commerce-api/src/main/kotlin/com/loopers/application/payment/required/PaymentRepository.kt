package com.loopers.application.payment.required

import com.loopers.domain.payment.Payment
import org.springframework.data.repository.Repository

/**
 * 결제 저장소. Spring Data가 구현을 만든다. 주문 식별자, 멱등 키, PG 주문 번호는 각각 유일하므로 어느 것으로 찾아도 결제 하나이고,
 * 없으면 null이다. 멱등 키는 열의 `utf8mb4_bin`을 따라 대소문자까지 그대로 견준다(ADR 0025 규칙 2).
 */
interface PaymentRepository : Repository<Payment, Long> {
    fun save(payment: Payment): Payment

    fun findByOrderId(orderId: Long): Payment?

    fun findAllByOrderIdIn(orderIds: Collection<Long>): List<Payment>

    fun findByIdempotencyKey(idempotencyKey: String): Payment?

    /** PG의 알림이 가리키는 결제. `UK_PAYMENT_ON_PG_ORDER_ID`를 탄다(ADR 0029 규칙 3, ADR 0031 규칙 2). */
    fun findByPgOrderId(pgOrderId: String): Payment?
}
