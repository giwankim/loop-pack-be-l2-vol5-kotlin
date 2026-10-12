package com.loopers.application.order.provided

import com.loopers.domain.order.Order
import jakarta.validation.Valid

/**
 * 고객의 주문 생성(주문을 생성하다).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface OrderCreator {
    /**
     * 품목·수량과 그때의 이름·단가를 확정 전 주문으로 남긴다. 요청마다 새 확정 전 주문이다.
     * 같은 품목을 다시 보내면 주문이 하나 더 생긴다(ADR 0005).
     * 없거나 삭제된 상품, 삭제된 브랜드의 상품이 담기면 `ORDER_PRODUCT_NOT_AVAILABLE`을 던진다.
     */
    fun create(userId: Long, @Valid request: OrderCreateRequest): Order
}
