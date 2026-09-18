package com.loopers.domain.order

import com.loopers.domain.shared.PageSlice

interface OrderRepository {
    fun save(order: Order): Order

    fun findByIdAndUserId(id: Long, userId: Long): Order?

    fun findByUserIdAndCreationKey(userId: Long, creationKey: String): Order?

    /**
     * [userId] 사용자가 만든 주문 한 조각. 늦게 만든 주문이 앞서고, 만든 시각이 같으면 나중에 받은 식별자가 앞선다.
     */
    fun findAllByUserId(userId: Long, page: Int, size: Int): PageSlice<Order>
}
