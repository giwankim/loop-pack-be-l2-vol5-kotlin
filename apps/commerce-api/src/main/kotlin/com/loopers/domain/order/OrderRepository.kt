package com.loopers.domain.order

interface OrderRepository {
    fun save(order: Order): Order

    fun findByIdAndUserId(id: Long, userId: Long): Order?

    fun findByUserIdAndCreationKey(userId: Long, creationKey: String): Order?
}
