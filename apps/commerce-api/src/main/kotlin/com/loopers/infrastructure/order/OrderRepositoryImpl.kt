package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderRepository
import org.springframework.stereotype.Component

@Component
class OrderRepositoryImpl(private val jpaRepository: OrderJpaRepository) : OrderRepository {
    override fun save(order: Order): Order = jpaRepository.save(order)

    override fun findByIdAndUserId(id: Long, userId: Long): Order? = jpaRepository.findByIdAndUserId(id, userId)

    override fun findByUserIdAndCreationKey(userId: Long, creationKey: String): Order? =
        jpaRepository.findByUserIdAndCreationKey(userId, creationKey)
}
