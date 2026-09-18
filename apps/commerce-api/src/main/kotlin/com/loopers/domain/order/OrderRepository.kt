package com.loopers.domain.order

import com.loopers.domain.shared.PageSlice

interface OrderRepository {
    fun save(order: Order): Order

    /** 소유자를 묻지 않는 조회. 관리자만 쓴다([findByIdAndUserId]가 고객의 것이다). */
    fun findById(id: Long): Order?

    fun findByIdAndUserId(id: Long, userId: Long): Order?

    fun findByUserIdAndCreationKey(userId: Long, creationKey: String): Order?

    /**
     * 늦게 만든 주문이 앞서는 한 조각. 만든 시각이 같으면 나중에 받은 식별자가 앞선다.
     * [userId]가 있으면 그 사용자가 만든 주문만 고르고, 없으면 모든 사용자의 주문을 본다.
     *
     * 내 목록(#15)과 관리자 목록(#16)이 이 하나를 쓴다. 차례를 정하는 규칙이 적히는 자리를 둘로 늘리지 않기
     * 위해서다(설계 12.4). 요청자의 식별자를 넣는 호출에는 남의 주문이 오르지 않는다.
     */
    fun findAll(userId: Long?, page: Int, size: Int): PageSlice<Order>
}
