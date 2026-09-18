package com.loopers.infrastructure.order

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.config.jpa.QueryDslConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderProduct
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.infrastructure.brand.BrandRepositoryImpl
import com.loopers.infrastructure.product.ProductRepositoryImpl
import com.loopers.infrastructure.user.UserRepositoryImpl
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

/**
 * [OrderRepositoryImpl]이 [OrderRepository] 계약을 실제 MySQL에서 지키는지 확인한다.
 * 설정과 정리 방식, 패키지 위치의 이유는 [com.loopers.infrastructure.product.ProductRepositoryTest]와 같다.
 *
 * 주문은 사용자와 상품을 식별자로 가리키고 그 참조에 물리 FK가 있으므로 세 저장소를 함께 등록한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    DataSourceConfig::class,
    QueryDslConfig::class,
    MySqlTestContainersConfig::class,
    BrandRepositoryImpl::class,
    ProductRepositoryImpl::class,
    UserRepositoryImpl::class,
    OrderRepositoryImpl::class,
)
class OrderRepositoryTest(
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val entityManager: EntityManager,
) {
    /**
     * 만든 시각은 [Order]가 스스로 정하므로 동률을 요청으로 만들 수 없다. 저장한 뒤 SQL로 시각을 겹쳐 놓고
     * 남은 차례를 식별자가 가르는지 본다.
     */
    @Test
    fun `findAllByUserId lists only that user's orders from the newest and breaks equal creation times by id`() {
        val owner = userRepository.save(User())
        val other = userRepository.save(User())
        val product = registerProduct()
        val older = orderRepository.save(order(owner.id, "older", product))
        val tied = orderRepository.save(order(owner.id, "tied", product))
        val tiedLater = orderRepository.save(order(owner.id, "tied-later", product))
        val foreign = orderRepository.save(order(other.id, "foreign", product))
        entityManager.flushAndClear()
        setCreatedAt(older.id, "2026-09-17 10:00:00.000000")
        setCreatedAt(tied.id, "2026-09-18 10:00:00.000000")
        setCreatedAt(tiedLater.id, "2026-09-18 10:00:00.000000")
        setCreatedAt(foreign.id, "2026-09-19 10:00:00.000000")
        entityManager.flushAndClear()

        val slice = orderRepository.findAllByUserId(owner.id, page = 0, size = 10)

        assertAll(
            { assertThat(slice.items.map { it.id }).containsExactly(tiedLater.id, tied.id, older.id) },
            { assertThat(slice.page).isZero() },
            { assertThat(slice.size).isEqualTo(10) },
            { assertThat(slice.hasNext).isFalse() },
        )
    }

    /**
     * 조각의 크기는 주문의 개수다. 품목을 함께 읽는 조인에 `limit`을 걸면 품목이 여럿인 주문에서
     * 주문이 잘리거나 품목이 모자라게 실린다. 그래서 품목이 셋인 주문만으로 쪽을 넘긴다.
     */
    @Test
    fun `findAllByUserId pages multi item orders by order count and keeps every item in product order`() {
        val owner = userRepository.save(User())
        val shirt = registerProduct("티셔츠")
        val socks = registerProduct("양말")
        val pants = registerProduct("바지")
        val first = orderRepository.save(order(owner.id, "first", shirt, socks, pants))
        val second = orderRepository.save(order(owner.id, "second", pants, socks, shirt))
        entityManager.flushAndClear()

        val firstPage = orderRepository.findAllByUserId(owner.id, page = 0, size = 1)
        val secondPage = orderRepository.findAllByUserId(owner.id, page = 1, size = 1)
        val thirdPage = orderRepository.findAllByUserId(owner.id, page = 2, size = 1)

        val productIds = listOf(shirt, socks, pants).map { it.id }.sorted()
        assertAll(
            { assertThat(firstPage.items.map { it.id }).containsExactly(second.id) },
            { assertThat(firstPage.hasNext).isTrue() },
            { assertThat(firstPage.items.single().items.map { it.productId }).containsExactlyElementsOf(productIds) },
            { assertThat(secondPage.items.map { it.id }).containsExactly(first.id) },
            { assertThat(secondPage.hasNext).isFalse() },
            { assertThat(secondPage.items.single().items.map { it.productId }).containsExactlyElementsOf(productIds) },
            { assertThat(thirdPage.items).isEmpty() },
            { assertThat(thirdPage.page).isEqualTo(2) },
            { assertThat(thirdPage.hasNext).isFalse() },
        )
    }

    private fun setCreatedAt(orderId: Long, createdAt: String) {
        entityManager.createNativeQuery("update orders set created_at = :createdAt where id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", orderId)
            .executeUpdate()
    }

    private fun order(userId: Long, creationKey: String, vararg products: Product): Order =
        Order(userId, creationKey, products.map { OrderProduct(it.id, it.name, it.price, 1) })

    private fun registerProduct(name: String = "티셔츠"): Product {
        val brand = brandRepository.save(Brand("루퍼스"))
        return productRepository.save(Product(brand = brand, name = name, price = Money(1_000), stock = Stock(1)))
    }
}
