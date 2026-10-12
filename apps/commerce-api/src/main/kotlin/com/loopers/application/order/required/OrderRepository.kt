package com.loopers.application.order.required

import com.loopers.domain.order.Order
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.Repository

/**
 * 주문 저장소. Spring Data가 구현을 만든다.
 *
 * 주문 하나는 품목까지 한 애그리거트이므로 엔티티 그래프로 품목을 함께 읽는다. 그래서 트랜잭션이 끝난 뒤 지연 로딩할 것이
 * 남지 않는다(ADR 0014). 품목 없는 주문을 원하는 호출자가 없어 지연으로 읽는 짝은 두지 않는다. 없으면 null이다.
 *
 * 주문 목록은 여기 없다. 사용자 필터가 조각마다 있거나 없어 이름만으로 끝나지 않으므로 QueryDSL로 짜고(카탈로그 설계 5.32),
 * application은 QueryDSL을 모르므로 [OrderListRepository]가 따로 맡는다.
 */
interface OrderRepository : Repository<Order, Long> {
    fun save(order: Order): Order

    /** 소유자를 묻지 않는 조회. 관리자만 쓴다([findWithLineItemsByIdAndUserId]가 고객의 것이다). */
    @EntityGraph(attributePaths = ["lineItems"])
    fun findWithLineItemsById(id: Long): Order?

    @EntityGraph(attributePaths = ["lineItems"])
    fun findWithLineItemsByIdAndUserId(id: Long, userId: Long): Order?

    /**
     * [userId] 사용자의 [id] 주문을 품목과 함께 `FOR UPDATE`로 잠가 읽는다. 없거나 남의 주문이면 null이다(ADR 0019).
     * `find`와 `By` 사이는 Spring Data가 설명으로 보므로 `@Query` 없이 [findWithLineItemsByIdAndUserId]와 같은 파생 조회다.
     *
     * 잠금은 품목을 조인하는 바로 그 문장에 걸리고(`for update of` 주문 별칭) 주문 행만 잠근다. Hibernate가 읽은 뒤 따로 잠그면
     * 메모리의 주문이 잠금 전 스냅샷으로 남는다. 잠금 읽기는 기다린 뒤 가장 최근에 커밋된 행을 읽으므로 그 사이의 확정도 본다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = ["lineItems"])
    fun findForUpdateWithLineItemsByIdAndUserId(id: Long, userId: Long): Order?
}
