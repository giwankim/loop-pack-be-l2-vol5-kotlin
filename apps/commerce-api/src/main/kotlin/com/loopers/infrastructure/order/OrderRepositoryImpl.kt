package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.QOrder.order
import com.loopers.domain.shared.PageSlice
import com.loopers.infrastructure.shared.fetchSlice
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * [OrderRepository]의 구현. 메서드 이름만으로 끝나는 일은 [OrderJpaRepository]에 맡기고, 목록만 QueryDSL로 짠다.
 * 두 인터페이스를 하나로 합치지 않는 이유는 [com.loopers.infrastructure.product.ProductRepositoryImpl]과 같다.
 */
@Component
class OrderRepositoryImpl(
    private val jpaRepository: OrderJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : OrderRepository {
    override fun save(order: Order): Order = jpaRepository.save(order)

    override fun findById(id: Long): Order? = jpaRepository.findByIdOrNull(id)

    override fun findByIdAndUserId(id: Long, userId: Long): Order? = jpaRepository.findByIdAndUserId(id, userId)

    override fun findByUserIdAndCreationKey(userId: Long, creationKey: String): Order? =
        jpaRepository.findByUserIdAndCreationKey(userId, creationKey)

    /**
     * 조각은 주문만 센다. 품목을 fetch join으로 함께 읽으면 `limit`이 주문이 아니라 조인된 행을 자르므로
     * 품목이 여럿인 주문에서 조각의 크기가 뒤틀린다(설계 9 조회, 14.1).
     *
     * 그래서 주문 루트만으로 조각을 정하고, 그 주문들의 품목을 읽기 트랜잭션 안에서 한 번 더 읽는다(설계 16.1).
     * 총 개수를 세는 쿼리는 나가지 않는다(카탈로그 설계 5.5). 조각을 만드는 규칙은 [fetchSlice] 하나에 있다.
     *
     * 사용자 필터가 조각마다 있거나 없으므로 이름만으로 끝나지 않아 QueryDSL로 짠다(카탈로그 설계 5.32).
     * 내 목록과 관리자 목록이 이 하나를 쓰므로 차례를 정하는 규칙도 한 자리에만 적힌다.
     */
    override fun findAll(userId: Long?, page: Int, size: Int): PageSlice<Order> {
        val slice = queryFactory
            .selectFrom(order)
            .where(userId?.let { order.userId.eq(it) })
            .orderBy(order.createdAt.desc(), order.id.desc())
            .fetchSlice(page, size)
        loadItemsOf(slice.items)
        return slice
    }

    /**
     * 조각에 오른 주문들의 품목을 한 번에 읽는다. 품목이 실리는 것은 부르는 쪽의 영속성 컨텍스트가 쥐고 있는
     * 인스턴스이므로, [orders]를 돌려준 조회와 같은 컨텍스트 안에서 불러야 한다. 컨텍스트가 다르면 이 조회는
     * 제 인스턴스를 채우고 [orders]의 품목은 비어 있다. 두 호출자 모두 [findAll]의 같은 읽기 트랜잭션
     * 안이라 성립한다(설계 16.1). 최신순 차례는 첫 조회의 것이 남는다.
     * `open-in-view=false`라 이 트랜잭션을 벗어난 뒤에는 지연 로딩이 없다.
     *
     * 명시적으로 읽는 까닭은 `jpa.yml`의 `default_batch_fetch_size`에 기대지 않기 위해서다. 그 값이 조각의
     * 최대 크기보다 작아지면 지연 로딩은 조용히 여러 번 나간다. 이 조회는 조각의 크기와 무관하게 한 번이다.
     *
     * 쓰는 것은 반환이 아니라 컬렉션이 채워지는 일이다. 품목마다 행이 늘어 주문이 여러 번 실려 오지만
     * 버리는 목록이라 `distinct`를 걸지 않는다. 차례도 첫 조회가 정했으므로 여기서 정하지 않는다.
     */
    private fun loadItemsOf(orders: List<Order>) {
        if (orders.isEmpty()) return
        queryFactory
            .selectFrom(order)
            .leftJoin(order.lineItems).fetchJoin()
            .where(order.id.`in`(orders.map { it.id }))
            .fetch()
    }
}
