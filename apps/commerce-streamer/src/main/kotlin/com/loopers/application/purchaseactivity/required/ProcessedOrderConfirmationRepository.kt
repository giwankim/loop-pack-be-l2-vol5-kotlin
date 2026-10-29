package com.loopers.application.purchaseactivity.required

import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmation
import org.springframework.data.repository.Repository

/**
 * The processed order confirmation repository. Spring Data builds it. The event ID and the order ID are each unique, so either
 * finds at most one confirmation, and a missing one is null. Recording reads by event ID first and then by order ID, and the
 * unique keys back that read when two consumers record the same event at once (ADR 0033 rule 3).
 */
interface ProcessedOrderConfirmationRepository : Repository<ProcessedOrderConfirmation, Long> {
    fun save(processedOrderConfirmation: ProcessedOrderConfirmation): ProcessedOrderConfirmation

    fun findByEventId(eventId: String): ProcessedOrderConfirmation?

    fun findByOrderId(orderId: Long): ProcessedOrderConfirmation?
}
