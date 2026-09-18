package com.loopers.infrastructure.product

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.ProductSort
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandRepositoryImpl
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

/**
 * [ProductRepositoryImpl]이 [ProductRepository] 계약을 실제 MySQL에서 지키는지 확인한다. 구현 클래스는 등록만 하고 부르는 것은 인터페이스다.
 * 상품이 브랜드를 참조하므로 [BrandRepositoryImpl]도 함께 등록한다. 설정과 정리 방식, 패키지 위치의 이유는
 * [com.loopers.infrastructure.brand.BrandRepositoryTest]와 같다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig::class, MySqlTestContainersConfig::class, BrandRepositoryImpl::class, ProductRepositoryImpl::class)
class ProductRepositoryTest(
    private val productRepository: ProductRepository,
    private val brandRepository: BrandRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `findById reads a saved product back with its brand, price, and stock after flush and clear`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val saved = productRepository.save(product(brand, price = 12_000, stock = 7))
        entityManager.flushAndClear()

        val found = productRepository.findById(saved.id)

        assertAll(
            { assertThat(found).isNotNull().isNotSameAs(saved) },
            { assertThat(found?.id).isEqualTo(saved.id) },
            { assertThat(found?.brand?.id).isEqualTo(brand.id) },
            { assertThat(found?.brand?.name).isEqualTo("루퍼스") },
            { assertThat(found?.name).isEqualTo("티셔츠") },
            { assertThat(found?.price).isEqualTo(Money(12_000)) },
            { assertThat(found?.stock).isEqualTo(Stock(7)) },
            { assertThat(found?.createdAt).isNotNull() },
            { assertThat(found?.updatedAt).isNotNull() },
            { assertThat(found?.deletedAt).isNull() },
        )
    }

    @Test
    fun `findById leaves the brand as an uninitialized proxy until a field other than id is read`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val saved = productRepository.save(product(brand))
        entityManager.flushAndClear()

        val found = productRepository.findById(saved.id)!!

        // 엔티티와 BaseEntity 가 allOpen 으로 열려 있어야 Hibernate 가 Brand 서브클래스 프록시를 만든다
        // (commerce-api 와 modules/jpa 의 build.gradle.kts). 하나라도 final 이면 HHH000305 를 남기고 곧바로 조회한다.
        assertThat(Hibernate.isInitialized(found.brand)).isFalse()
        // 식별자는 프록시가 들고 있으므로 읽어도 초기화되지 않는다.
        assertThat(found.brand.id).isEqualTo(brand.id)
        assertThat(Hibernate.isInitialized(found.brand)).isFalse()
        // 다른 필드를 읽는 순간 브랜드를 조회한다.
        assertThat(found.brand.name).isEqualTo("루퍼스")
        assertThat(Hibernate.isInitialized(found.brand)).isTrue()
    }

    @Test
    fun `save stores price and stock in the price and stock_quantity columns`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val saved = productRepository.save(product(brand, price = 12_000, stock = 7))
        entityManager.flushAndClear()

        val row = entityManager
            .createNativeQuery("select brand_id, price, stock_quantity from product where id = :id")
            .setParameter("id", saved.id)
            .singleResult as Array<*>

        assertAll(
            { assertThat((row[0] as Number).toLong()).isEqualTo(brand.id) },
            { assertThat((row[1] as Number).toLong()).isEqualTo(12_000L) },
            { assertThat((row[2] as Number).toInt()).isEqualTo(7) },
        )
    }

    @Test
    fun `findById returns null for a deleted product`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val deleted = productRepository.save(product(brand).apply { delete() })
        entityManager.flushAndClear()

        val found = productRepository.findById(deleted.id)

        assertThat(found).isNull()
    }

    @Test
    fun `findById returns null for an unknown id`() {
        assertThat(productRepository.findById(999L)).isNull()
    }

    @Test
    fun `findAll returns the products of every brand, latest registered first`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val other = brandRepository.save(Brand("나이키"))
        val first = productRepository.save(product(brand))
        val second = productRepository.save(product(other))
        val third = productRepository.save(product(brand))
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LATEST)

        assertThat(slice.items.map { it.id }).containsExactly(third.id, second.id, first.id)
    }

    @Test
    fun `findAll sorted by price puts the cheapest first`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val dear = productRepository.save(product(brand, price = 30_000))
        val cheap = productRepository.save(product(brand, price = 10_000))
        val middling = productRepository.save(product(brand, price = 20_000))
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.PRICE_ASC)

        assertThat(slice.items.map { it.id }).containsExactly(cheap.id, middling.id, dear.id)
    }

    @Test
    fun `findAll sorted by price breaks a tie with the later id first`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val first = productRepository.save(product(brand, price = 10_000))
        val second = productRepository.save(product(brand, price = 10_000))
        val third = productRepository.save(product(brand, price = 10_000))
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.PRICE_ASC)

        assertThat(slice.items.map { it.id }).containsExactly(third.id, second.id, first.id)
    }

    /**
     * 등록 시각이 같은 상품은 나중에 받은 식별자가 앞선다. Hibernate가 만드는 `created_at`은 `datetime(6)`이라
     * 이어서 저장해도 시각이 저절로 같아지지는 않으므로, 동률을 native 쿼리로 만들어 고정한다.
     */
    @Test
    fun `findAll sorted by latest breaks a tie with the later id first`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val first = productRepository.save(product(brand))
        val second = productRepository.save(product(brand))
        val third = productRepository.save(product(brand))
        entityManager.flushAndClear()
        listOf(first, second, third).forEach { shareCreatedAt(it.id) }
        entityManager.clear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LATEST)

        assertThat(slice.items.map { it.id }).containsExactly(third.id, second.id, first.id)
    }

    /** 삭제된 브랜드를 가리키는 필터는 비어 있다. 브랜드가 살아 있지 않으면 그 아래 상품도 목록에 오르지 않는다. */
    @Test
    fun `findAll with a deleted brand's id is empty`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        productRepository.save(product(brand))
        brand.delete()
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = brand.id, page = 0, size = 20, sort = ProductSort.LATEST)

        assertThat(slice.items).isEmpty()
        assertThat(slice.hasNext).isFalse()
    }

    @Test
    fun `findAll leaves out deleted products`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val live = productRepository.save(product(brand))
        productRepository.save(product(brand).apply { delete() })
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LATEST)

        assertThat(slice.items.map { it.id }).containsExactly(live.id)
    }

    @Test
    fun `findAll with a brandId keeps only that brand's products`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val other = brandRepository.save(Brand("나이키"))
        val mine = productRepository.save(product(brand))
        productRepository.save(product(other))
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = brand.id, page = 0, size = 20, sort = ProductSort.LATEST)

        assertThat(slice.items.map { it.id }).containsExactly(mine.id)
    }

    @Test
    fun `findAll with an unknown brandId is empty`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        productRepository.save(product(brand))
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = 999L, page = 0, size = 20, sort = ProductSort.LATEST)

        assertThat(slice.items).isEmpty()
        assertThat(slice.hasNext).isFalse()
    }

    @Test
    fun `findAll reports hasNext while a later slice remains`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        repeat(3) { productRepository.save(product(brand)) }
        entityManager.flushAndClear()

        val first = productRepository.findAll(brandId = null, page = 0, size = 2, sort = ProductSort.LATEST)
        val second = productRepository.findAll(brandId = null, page = 1, size = 2, sort = ProductSort.LATEST)

        assertAll(
            { assertThat(first.items).hasSize(2) },
            { assertThat(first.hasNext).isTrue() },
            { assertThat(first.page).isZero() },
            { assertThat(first.size).isEqualTo(2) },
            { assertThat(second.items).hasSize(1) },
            { assertThat(second.hasNext).isFalse() },
            { assertThat(second.page).isEqualTo(1) },
        )
    }

    /**
     * `@EntityGraph`가 `@ManyToOne(optional = false)`를 inner join으로 읽고 [Brand]의 `@SQLRestriction`이 그 join에도 붙으므로,
     * 삭제된 브랜드에 달린 살아 있는 상품은 목록에서 빠진다. 상품 자체는 그대로 있다. 지금은 브랜드 삭제가 없어
     * 닿을 수 없는 상태이고, #6이 살아 있는 상품이 남은 브랜드의 삭제를 거절해 계속 닿을 수 없게 만든다(설계 7).
     */
    @Test
    fun `findAll leaves out a live product whose brand was deleted`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val live = productRepository.save(product(brand))
        brand.delete()
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LATEST)

        assertAll(
            { assertThat(productRepository.findById(live.id)).isNotNull() },
            { assertThat(slice.items).isEmpty() },
        )
    }

    private fun product(brand: Brand, price: Long = 10_000, stock: Int = 1) =
        Product(brand = brand, name = "티셔츠", price = Money(price), stock = Stock(stock))

    /**
     * 등록 시각을 모든 상품이 같은 값으로 갖게 한다. `created_at`은 `@Column(updatable = false)`지만
     * 그것은 JPA의 UPDATE만 막는 것이고 native 쿼리는 영속성 컨텍스트를 거치지 않는다.
     */
    private fun shareCreatedAt(productId: Long) {
        entityManager
            .createNativeQuery("update product set created_at = :at where id = :id")
            .setParameter("at", SHARED_CREATED_AT)
            .setParameter("id", productId)
            .executeUpdate()
    }

    companion object {
        private const val SHARED_CREATED_AT = "2026-01-01 00:00:00.000000"
    }
}
