package com.loopers.application.order.provided

import com.loopers.domain.order.Order
import jakarta.validation.Valid
import org.springframework.data.domain.Slice

/**
 * 주문 슬라이스가 내주는 읽기. 고객의 내 주문 상세·목록과 관리자의 주문 목록·상세가 부르고, 주문 확정은 [findForUpdate]로 바꿀 주문을 잠가 얻는다.
 * 결제 상태까지 싣는 읽기는 [OrderInfo]를 돌려준다. 응답에 실리는 읽기가 모두 그렇다.
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
     * 요청자의 주문 하나와 그 결제 상태. 고객의 주문 상세가 부른다. 결제수단이 PG인 주문만 결제를 읽는다(ADR 0028 규칙 1).
     * 없는 주문도 남의 주문도 [find]처럼 `ORDER_NOT_FOUND`다.
     */
    fun findInfo(userId: Long, orderId: Long): OrderInfo

    /**
     * 요청자의 주문 하나를 품목과 함께 잠가 읽는다. 없는 주문도 남의 주문도 [find]처럼 `ORDER_NOT_FOUND`다.
     * 주문 확정이 첫 문장에서 부른다. 같은 주문의 확정은 이 잠금에서 줄을 서고, 기다린 쪽은 잠금을 얻은 뒤 가장 최근에 커밋된 주문을 읽는다(ADR 0019).
     *
     * 호출자의 쓰기 트랜잭션 안에서만 부른다. 잠금은 그 트랜잭션이 끝날 때 풀린다.
     */
    fun findForUpdate(userId: Long, orderId: Long): Order

    /**
     * 요청자가 만든 주문 한 조각과 주문마다의 결제 상태. 늦게 만든 주문이 앞선다. 받는 사용자 식별자는 요청자 하나뿐이라
     * 남의 목록을 내줄 길이 없다(카탈로그 설계 5.30). 결제수단이 PG인 주문의 결제는 조각마다 한 번에 읽는다(ADR 0028 규칙 1).
     */
    fun findAll(userId: Long, @Valid request: OrderListRequest): Slice<OrderInfo>

    /**
     * 관리자가 보는 주문 한 조각과 주문마다의 결제 상태. [OrderAdminListRequest.userId]가 있으면 그 사용자가 만든 주문만 고른다.
     * 결제는 고객 목록처럼 조각마다 한 번에 읽는다.
     *
     * 소유권을 묻지 않는다는 것이 이름이나 입력에 드러나야 하므로 관리자 조회는 [OrderAdminListRequest]를 받는다.
     * 요청자를 넣는 [findAll]과 차례가 같으며, 거르는 사용자가 없을 수 있다는 것만 다르다.
     */
    fun findAll(@Valid request: OrderAdminListRequest): Slice<OrderInfo>

    /**
     * 관리자가 보는 주문 하나와 그 결제 상태. 주문한 사용자가 누구든 저장된 스냅샷을 그대로 준다. 없으면 `ORDER_NOT_FOUND`다.
     * 결제는 고객의 [findInfo]처럼 결제수단이 PG인 주문만 읽는다.
     *
     * 이름에 역할을 적은 까닭은 고객의 [find]·[findInfo]와 파라미터만으로는 갈리지 않기 때문이다. 모두 `Long`을 받으므로
     * 소유권을 묻지 않는 쪽을 실수로 고객 경로에서 부를 수 있다. 수식어가 붙는 쪽이 관리자인 것은
     * 고객 쪽이 기본이기 때문이다(CONTEXT.md 고객). 목록은 [OrderAdminListRequest]가 그 일을 한다.
     */
    fun findForAdmin(orderId: Long): OrderInfo
}
