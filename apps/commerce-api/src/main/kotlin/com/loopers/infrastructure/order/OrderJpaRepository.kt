package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import org.springframework.data.jpa.repository.JpaRepository

/**
 * [Order]의 Spring Data JPA 저장소. 목록은 여기 없다. 사용자 필터가 조각마다 있거나 없어 이름만으로 끝나지
 * 않으므로 [OrderRepositoryImpl]이 QueryDSL로 짠다(카탈로그 설계 5.32).
 */
interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByIdAndUserId(id: Long, userId: Long): Order?

    fun findByUserIdAndCreationKey(userId: Long, creationKey: String): Order?
}
