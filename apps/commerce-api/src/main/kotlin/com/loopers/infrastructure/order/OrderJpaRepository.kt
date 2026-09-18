package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository

interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByIdAndUserId(id: Long, userId: Long): Order?

    fun findByUserIdAndCreationKey(userId: Long, creationKey: String): Order?

    /**
     * 차례가 주문의 컬럼 둘로만 정해지고 필터도 사용자 하나뿐이라 메서드 이름으로 끝난다.
     * 품목을 함께 읽지 않는 까닭은 [OrderRepositoryImpl.findAllByUserId]에 적었다.
     */
    fun findAllByUserIdOrderByCreatedAtDescIdDesc(userId: Long, pageable: Pageable): Slice<Order>
}
