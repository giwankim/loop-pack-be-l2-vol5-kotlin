package com.loopers.application.purchaseactivity.required

import com.loopers.domain.purchaseactivity.PurchaseActivity
import org.springframework.data.repository.Repository

/**
 * The purchase activity repository. Spring Data builds it. An order has one activity per product, and they come back in no
 * particular order: recording compares them with an event's items as a set of products and quantities (purchase activity
 * design 4).
 */
interface PurchaseActivityRepository : Repository<PurchaseActivity, Long> {
    fun save(purchaseActivity: PurchaseActivity): PurchaseActivity

    /** The same name and parameter as `CrudRepository.saveAll`, so it goes to `SimpleJpaRepository`, which saves each one. */
    fun saveAll(purchaseActivities: Iterable<PurchaseActivity>): List<PurchaseActivity>

    fun findAllByOrderId(orderId: Long): List<PurchaseActivity>
}
