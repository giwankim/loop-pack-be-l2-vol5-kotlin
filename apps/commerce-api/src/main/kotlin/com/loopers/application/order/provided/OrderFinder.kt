package com.loopers.application.order.provided

import com.loopers.domain.order.Order
import jakarta.validation.Valid
import org.springframework.data.domain.Slice

/**
 * 주문 조각이 내주는 읽기. 고객의 내 주문 상세·목록과 관리자의 주문 목록·상세가 부르고, 주문 확정은 [findForUpdate]로 바꿀 주문을 잠가 얻는다.
 * 품목은 같은 애그리거트라 함께 읽어 [Order]를 통째로 돌려준다. 트랜잭션 밖에서 품목을 건너도 지연 로딩이 없다(ADR 0014).
 *
 * 저장된 스냅샷만 싣는다. 상품을 다시 읽어 이름·단가를 채우지 않으므로 이름이 바뀌거나 삭제된 상품의 주문도
 * 만들 때의 값 그대로다(ADR 0002, 설계 9 조회).
 *
 * 고객 읽기의 `userId`는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface OrderFinder {
    /** 요청자의 주문 하나. 없는 주문도 남의 주문도 `ORDER_NOT_FOUND`다. */
    fun find(userId: Long, orderId: Long): Order

    /**
     * 요청자의 주문 하나를 품목과 함께 잠가 읽는다. 없는 주문도 남의 주문도 [find]처럼 `ORDER_NOT_FOUND`다.
     * 주문 확정이 첫 문장에서 부른다. 같은 주문의 확정은 이 잠금에서 줄을 서고, 기다린 쪽은 잠금을 얻은 뒤 가장 최근에 커밋된 주문을 읽는다(ADR 0019).
     *
     * 호출자의 쓰기 트랜잭션 안에서만 부른다. 잠금은 그 트랜잭션이 끝날 때 풀린다.
     */
    fun findForUpdate(userId: Long, orderId: Long): Order

    /**
     * 요청자가 만든 주문 한 조각. 늦게 만든 주문이 앞선다. 받는 사용자 식별자는 요청자 하나뿐이라
     * 남의 목록을 내줄 길이 없다(카탈로그 설계 5.30).
     */
    fun findAll(userId: Long, @Valid request: OrderListRequest): Slice<Order>

    /**
     * 관리자가 보는 주문 한 조각. [OrderAdminListRequest.userId]가 있으면 그 사용자가 만든 주문만 고른다.
     *
     * 소유권을 묻지 않는다는 것이 이름이나 입력에 드러나야 하므로 관리자 조회는 [OrderAdminListRequest]를 받는다.
     * 요청자를 넣는 [findAll]과 차례가 같으며, 거르는 사용자가 없을 수 있다는 것만 다르다.
     */
    fun findAll(@Valid request: OrderAdminListRequest): Slice<Order>

    /**
     * 관리자가 보는 주문 하나. 주문한 사용자가 누구든 저장된 스냅샷을 그대로 준다. 없으면 `ORDER_NOT_FOUND`다.
     *
     * 이름에 역할을 적은 까닭은 [find]와 파라미터만으로는 갈리지 않기 때문이다. 둘 다 `Long`을 받으므로
     * 소유권을 묻지 않는 쪽을 실수로 고객 경로에서 부를 수 있다. 수식어가 붙는 쪽이 관리자인 것은
     * 고객 쪽이 기본이기 때문이다(CONTEXT.md 고객). 목록은 [OrderAdminListRequest]가 그 일을 한다.
     */
    fun findForAdmin(orderId: Long): Order
}
