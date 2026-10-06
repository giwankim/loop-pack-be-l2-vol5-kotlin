package com.loopers.adapter.persistence.order

import com.loopers.domain.order.Order
import org.springframework.data.repository.Repository

/**
 * [Order]의 Spring Data JPA 저장소. 목록은 여기 없다. 사용자 필터가 조각마다 있거나 없어 이름만으로 끝나지
 * 않으므로 [OrderRepositoryImpl]이 QueryDSL로 짠다(카탈로그 설계 5.32).
 */
interface OrderJpaRepository : Repository<Order, Long> {
    fun save(order: Order): Order

    /**
     * `CrudRepository.findById`와 이름·매개변수가 같아 `EntityManager.find`로 간다.
     * 없으면 null이다. 반환을 non-null로 적으면 없을 때 예외를 던진다(카탈로그 설계 5.20).
     */
    fun findById(id: Long): Order?

    fun findByIdAndUserId(id: Long, userId: Long): Order?
}
