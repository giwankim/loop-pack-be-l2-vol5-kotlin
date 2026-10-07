package com.loopers.application.order.required

import com.loopers.adapter.persistence.order.QuerydslOrderListRepository
import com.loopers.config.jpa.QueryDslConfig
import com.loopers.domain.order.Order
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest

/**
 * Spring Data가 만든 [OrderRepository]와 QueryDSL로 짠 [OrderListRepository]가 실제 MySQL에서 계약을 지키는지 확인한다.
 * 설정과 패키지 위치, 목록의 구현 [QuerydslOrderListRepository]를 직접 가져오는 이유는
 * [com.loopers.application.product.required.ProductRepositoryTest]와 같다.
 *
 * 주문은 사용자와 상품을 식별자로만 가리키지만 두 참조에 물리 외래 키가 있으므로(설계 13) 준비 단계가 기반 클래스의
 * `prepare`로 사용자·브랜드·상품의 실제 행을 만들고, 주문은 그 상품을 스냅숏한 품목으로 저장한다.
 *
 * `findAll`은 내 목록(#15)과 관리자 목록(#16)이 함께 쓴다. 그래서 거를 사용자를 넣은 경우와 넣지 않은 경우를
 * 한 클래스에서 함께 본다(설계 16.2).
 */
@Import(QueryDslConfig::class, QuerydslOrderListRepository::class)
class OrderRepositoryTest(
    private val orderListRepository: OrderListRepository,
) : BaseRepositoryTest() {
    /**
     * 만든 시각은 [Order]가 스스로 정하므로 동률을 요청으로 만들 수 없다. 저장한 뒤 SQL로 시각을 겹쳐 놓고
     * 남은 차례를 식별자가 가르는지 본다. 남의 주문에는 가장 늦은 시각을 주어, 걸러 내는 일이 차례보다 먼저임을 본다.
     */
    @Test
    fun `findAll with a user lists only that user's orders from the newest and breaks equal creation times by id`() {
        val owner = prepareUser()
        val other = prepareUser()
        prepareProduct()
        val older = prepareOrder(owner, listOf(product))
        val tied = prepareOrder(owner, listOf(product))
        val tiedLater = prepareOrder(owner, listOf(product))
        val foreign = prepareOrder(other, listOf(product))
        entityManager.flushAndClear()
        setCreatedAt(older.id, "2026-09-17 10:00:00.000000")
        setCreatedAt(tied.id, "2026-09-18 10:00:00.000000")
        setCreatedAt(tiedLater.id, "2026-09-18 10:00:00.000000")
        setCreatedAt(foreign.id, "2026-09-19 10:00:00.000000")
        entityManager.flushAndClear()

        val slice = orderListRepository.findAll(userId = owner.id, pageable = PageRequest.of(0, 10))

        assertThat(slice.content.map { it.id }).containsExactly(tiedLater.id, tied.id, older.id)
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(10)
        assertThat(slice.hasNext()).isFalse()
    }

    /**
     * 조각의 크기는 주문의 개수다. 품목을 함께 읽는 조인에 `limit`을 걸면 품목이 여럿인 주문에서
     * 주문이 잘리거나 품목이 모자라게 실린다. 그래서 품목이 셋인 주문만으로 쪽을 넘긴다.
     */
    @Test
    fun `findAll pages multi item orders by order count and keeps every item in product order`() {
        prepareUser()
        prepareBrand()
        val products = List(3) { prepareProduct(brand) }
        val first = prepareOrder(user, products)
        val second = prepareOrder(user, products.reversed())
        entityManager.flushAndClear()

        val firstPage = orderListRepository.findAll(user.id, pageable = PageRequest.of(0, 1))
        val secondPage = orderListRepository.findAll(user.id, pageable = PageRequest.of(1, 1))
        val thirdPage = orderListRepository.findAll(user.id, pageable = PageRequest.of(2, 1))

        val productIds = products.map { it.id }.sorted()
        assertThat(firstPage.content.map { it.id }).containsExactly(second.id)
        assertThat(firstPage.hasNext()).isTrue()
        assertThat(firstPage.number).isZero()
        assertThat(firstPage.size).isOne()
        assertThat(firstPage.content.single().items.map { it.productId }).containsExactlyElementsOf(productIds)
        assertThat(secondPage.content.map { it.id }).containsExactly(first.id)
        assertThat(secondPage.hasNext()).isFalse()
        assertThat(secondPage.number).isEqualTo(1)
        assertThat(secondPage.size).isOne()
        assertThat(secondPage.content.single().items.map { it.productId }).containsExactlyElementsOf(productIds)
        assertThat(thirdPage.content).isEmpty()
        assertThat(thirdPage.number).isEqualTo(2)
        assertThat(thirdPage.hasNext()).isFalse()
        assertThat(thirdPage.size).isOne()
    }

    @Test
    fun `findAll has no next slice when the orders fill the page exactly`() {
        prepareUser()
        prepareProduct()
        repeat(2) { prepareOrder(user, listOf(product)) }
        entityManager.flushAndClear()

        val slice = orderListRepository.findAll(user.id, pageable = PageRequest.of(0, 2))

        assertThat(slice.content).hasSize(2)
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(2)
    }

    /** 거를 사용자가 없으면 모든 사용자의 주문이 한 조각에 오른다. 관리자 목록이 쓰는 길이다. */
    @Test
    fun `findAll without a user gives the orders of every user latest first`() {
        prepareProduct()
        val first = prepareOrder(products = listOf(product))
        val second = prepareOrder(products = listOf(product))
        entityManager.flushAndClear()

        val slice = orderListRepository.findAll(userId = null, pageable = PageRequest.of(0, 20))

        assertThat(slice.content.map { it.id }).containsExactly(second.id, first.id)
    }

    /**
     * 차례를 정하는 첫 기준은 만든 시각이고 식별자는 동률만 가른다. 준비가 주문을 차례로 만들면 두 차례가 늘 같아
     * 시각 기준이 사라져도 아무 테스트가 말하지 않는다. 그래서 나중에 받은 식별자의 시각을 앞으로 돌린다.
     */
    @Test
    fun `findAll puts the later created_at first even when its id is lower`() {
        prepareUser()
        prepareProduct()
        val recent = prepareOrder(user, listOf(product))
        val backDated = prepareOrder(user, listOf(product))
        entityManager.flushAndClear()
        setCreatedAt(backDated.id, "2026-09-17 00:00:00.000000")
        entityManager.flushAndClear()

        val slice = orderListRepository.findAll(userId = null, pageable = PageRequest.of(0, 20))

        assertThat(slice.content.map { it.id }).containsExactly(recent.id, backDated.id)
    }

    /**
     * `open-in-view=false`이므로 품목은 조회가 돌려주는 조각에 이미 실려 있어야 한다(설계 9 조회).
     * 조각을 받은 뒤 영속성 컨텍스트를 비워, 조회 밖에서 지연 로딩에 기대지 않는지 확인한다.
     * `default_batch_fetch_size`가 지연 로딩을 모아 주므로 쿼리 수만 세면 이 약속은 확인되지 않는다(설계 16.1).
     */
    @Test
    fun `findAll loads the items of every order in the slice`() {
        prepareUser()
        prepareBrand()
        val (shared, other) = List(2) { prepareProduct(brand) }
        prepareOrder(user, listOf(other, shared))
        prepareOrder(user, listOf(shared))
        entityManager.flushAndClear()

        val slice = orderListRepository.findAll(userId = null, pageable = PageRequest.of(0, 20))
        entityManager.clear()

        assertThat(slice.content.map { order -> order.items.map { it.productId } })
            .containsExactly(listOf(shared.id), listOf(shared.id, other.id).sorted())
    }

    @Test
    fun `findAll gives an empty slice without a next page when nothing matches`() {
        prepareUser()

        val slice = orderListRepository.findAll(userId = user.id, pageable = PageRequest.of(0, 20))

        assertThat(slice.content).isEmpty()
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(20)
    }

    private fun setCreatedAt(orderId: Long, createdAt: String) {
        entityManager.createNativeQuery("update orders set created_at = :createdAt where id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", orderId)
            .executeUpdate()
    }
}
