package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.shared.PageSlice
import com.loopers.infrastructure.shared.toPageSlice
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

@Component
class OrderRepositoryImpl(private val jpaRepository: OrderJpaRepository) : OrderRepository {
    override fun save(order: Order): Order = jpaRepository.save(order)

    override fun findByIdAndUserId(id: Long, userId: Long): Order? = jpaRepository.findByIdAndUserId(id, userId)

    override fun findByUserIdAndCreationKey(userId: Long, creationKey: String): Order? =
        jpaRepository.findByUserIdAndCreationKey(userId, creationKey)

    /**
     * 조각은 주문만 센다. 품목을 fetch join으로 함께 읽으면 `limit`이 주문이 아니라 조인된 행을 자르므로
     * 품목이 여럿인 주문에서 조각의 크기가 뒤틀린다(설계 9 조회).
     *
     * 그래서 주문 루트만 읽고 품목은 읽기 트랜잭션 안에서 뒤따라 읽는다. 항목마다 조회가 붙지 않는 것은
     * `jpa.yml`의 `default_batch_fetch_size`가 아직 읽지 않은 컬렉션을 한 번에 모아 읽어 주기 때문이고,
     * 그 값이 조각의 최대 크기와 같아 어떤 조각이든 품목 조회는 한 번이다.
     * 전역 설정에 기대는 약속이므로 `OrderServiceTest`가 조회 횟수를 세어 붙들어 둔다(설계 14.1).
     *
     * 차례는 쿼리 이름이 적으므로 [PageRequest]에는 조각의 위치와 크기만 싣는다.
     * 정렬을 함께 실으면 그 기준이 쿼리의 것을 덮는다([com.loopers.infrastructure.product.ProductRepositoryImpl]와 같다).
     */
    override fun findAllByUserId(userId: Long, page: Int, size: Int): PageSlice<Order> =
        jpaRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size)).toPageSlice()
}
