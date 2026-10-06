package com.loopers.application.order.required

import com.loopers.domain.order.Order
import com.loopers.domain.shared.PageSlice

/**
 * 주문 목록. 사용자 필터가 조각마다 있거나 없어 QueryDSL로 짜는데(카탈로그 설계 5.32), application은 QueryDSL을 모르므로
 * 구현은 adapter.persistence에 둔다. Spring Data의 저장소가 아닌 순수 포트라 [OrderRepository]와 따로 선언하는 까닭은
 * [com.loopers.application.product.required.ProductListRepository]와 같다.
 */
interface OrderListRepository {
    /**
     * 늦게 만든 주문이 앞서는 한 조각. 만든 시각이 같으면 나중에 받은 식별자가 앞선다.
     * [userId]가 있으면 그 사용자가 만든 주문만 고르고, 없으면 모든 사용자의 주문을 본다.
     * 조각에 오른 주문의 품목은 이미 실려 있다.
     *
     * 내 목록(#15)과 관리자 목록(#16)이 이 하나를 쓴다. 차례를 정하는 규칙이 적히는 자리를 둘로 늘리지 않기
     * 위해서다(설계 12.4). 요청자의 식별자를 넣는 호출에는 남의 주문이 오르지 않는다.
     */
    fun findAll(userId: Long?, page: Int, size: Int): PageSlice<Order>
}
