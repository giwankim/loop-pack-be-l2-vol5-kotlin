package com.loopers.application.order.required

import com.loopers.domain.order.Order
import org.springframework.data.repository.Repository

/**
 * 주문 저장소. Spring Data가 구현을 만든다.
 *
 * 주문 목록은 여기 없다. 사용자 필터가 조각마다 있거나 없어 이름만으로 끝나지 않으므로 QueryDSL로 짜고(카탈로그 설계 5.32),
 * application은 QueryDSL을 모르므로 [OrderListRepository]가 따로 맡는다.
 */
interface OrderRepository : Repository<Order, Long> {
    fun save(order: Order): Order

    /**
     * 소유자를 묻지 않는 조회. 관리자만 쓴다([findByIdAndUserId]가 고객의 것이다).
     *
     * `CrudRepository.findById`와 이름·매개변수가 같아 `EntityManager.find`로 간다.
     * 없으면 null이다. 반환을 non-null로 적으면 없을 때 예외를 던진다(카탈로그 설계 5.20).
     */
    fun findById(id: Long): Order?

    fun findByIdAndUserId(id: Long, userId: Long): Order?
}
