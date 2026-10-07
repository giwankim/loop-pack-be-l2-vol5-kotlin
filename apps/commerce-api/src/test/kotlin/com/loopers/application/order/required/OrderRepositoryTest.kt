package com.loopers.application.order.required

import com.loopers.adapter.persistence.order.QuerydslOrderListRepository
import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.application.user.required.UserRepository
import com.loopers.config.jpa.DataSourceConfig
import com.loopers.config.jpa.QueryDslConfig
import com.loopers.domain.brand.createBrand
import com.loopers.domain.order.Order
import com.loopers.domain.order.createOrder
import com.loopers.domain.order.createOrderProduct
import com.loopers.domain.product.createProduct
import com.loopers.domain.user.User
import com.loopers.support.flushAndClear
import com.loopers.testcontainers.MySqlTestContainersConfig
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest

/**
 * Spring Data가 만든 [OrderRepository]와 QueryDSL로 짠 [OrderListRepository]가 실제 MySQL에서 계약을 지키는지 확인한다.
 * 설정과 패키지 위치, 목록의 구현 [QuerydslOrderListRepository]를 직접 가져오는 이유는
 * [com.loopers.application.product.required.ProductRepositoryTest]와 같다.
 *
 * 주문은 사용자와 상품을 식별자로만 가리키지만 두 참조에 물리 외래 키가 있으므로(설계 13) 준비 단계가 사용자·브랜드·상품
 * 저장소로 실제 행을 만든다. 세 저장소는 Spring Data가 만든다.
 *
 * `findAll`은 내 목록(#15)과 관리자 목록(#16)이 함께 쓴다. 그래서 거를 사용자를 넣은 경우와 넣지 않은 경우를
 * 한 클래스에서 함께 본다(설계 16.2).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    DataSourceConfig::class,
    QueryDslConfig::class,
    MySqlTestContainersConfig::class,
    QuerydslOrderListRepository::class,
)
class OrderRepositoryTest(
    private val orderRepository: OrderRepository,
    private val orderListRepository: OrderListRepository,
    private val userRepository: UserRepository,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val entityManager: EntityManager,
) {
    /**
     * 만든 시각은 [Order]가 스스로 정하므로 동률을 요청으로 만들 수 없다. 저장한 뒤 SQL로 시각을 겹쳐 놓고
     * 남은 차례를 식별자가 가르는지 본다. 남의 주문에는 가장 늦은 시각을 주어, 걸러 내는 일이 차례보다 먼저임을 본다.
     */
    @Test
    fun `findAll with a user lists only that user's orders from the newest and breaks equal creation times by id`() {
        val owner = userRepository.save(User())
        val other = userRepository.save(User())
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        val older = orderRepository.save(createOrder(owner.id, listOf(createOrderProduct(product))))
        val tied = orderRepository.save(createOrder(owner.id, listOf(createOrderProduct(product))))
        val tiedLater = orderRepository.save(createOrder(owner.id, listOf(createOrderProduct(product))))
        val foreign = orderRepository.save(createOrder(other.id, listOf(createOrderProduct(product))))
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
        val owner = userRepository.save(User())
        val brand = brandRepository.save(createBrand())
        val products = List(3) { productRepository.save(createProduct(brand)) }
        val first = orderRepository.save(createOrder(owner.id, products.map { createOrderProduct(it) }))
        val second = orderRepository.save(createOrder(owner.id, products.reversed().map { createOrderProduct(it) }))
        entityManager.flushAndClear()

        val firstPage = orderListRepository.findAll(owner.id, pageable = PageRequest.of(0, 1))
        val secondPage = orderListRepository.findAll(owner.id, pageable = PageRequest.of(1, 1))
        val thirdPage = orderListRepository.findAll(owner.id, pageable = PageRequest.of(2, 1))

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
        val owner = userRepository.save(User())
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        repeat(2) { orderRepository.save(createOrder(owner.id, listOf(createOrderProduct(product)))) }
        entityManager.flushAndClear()

        val slice = orderListRepository.findAll(owner.id, pageable = PageRequest.of(0, 2))

        assertThat(slice.content).hasSize(2)
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(2)
    }

    /** 거를 사용자가 없으면 모든 사용자의 주문이 한 조각에 오른다. 관리자 목록이 쓰는 길이다. */
    @Test
    fun `findAll without a user gives the orders of every user latest first`() {
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        val first = orderRepository.save(createOrder(userRepository.save(User()).id, listOf(createOrderProduct(product))))
        val second = orderRepository.save(createOrder(userRepository.save(User()).id, listOf(createOrderProduct(product))))
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
        val owner = userRepository.save(User())
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        val recent = orderRepository.save(createOrder(owner.id, listOf(createOrderProduct(product))))
        val backDated = orderRepository.save(createOrder(owner.id, listOf(createOrderProduct(product))))
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
        val owner = userRepository.save(User())
        val brand = brandRepository.save(createBrand())
        val (shared, other) = List(2) { productRepository.save(createProduct(brand)) }
        orderRepository.save(createOrder(owner.id, listOf(other, shared).map { createOrderProduct(it) }))
        orderRepository.save(createOrder(owner.id, listOf(createOrderProduct(shared))))
        entityManager.flushAndClear()

        val slice = orderListRepository.findAll(userId = null, pageable = PageRequest.of(0, 20))
        entityManager.clear()

        assertThat(slice.content.map { order -> order.items.map { it.productId } })
            .containsExactly(listOf(shared.id), listOf(shared.id, other.id).sorted())
    }

    @Test
    fun `findAll gives an empty slice without a next page when nothing matches`() {
        val slice = orderListRepository.findAll(userId = userRepository.save(User()).id, pageable = PageRequest.of(0, 20))

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
