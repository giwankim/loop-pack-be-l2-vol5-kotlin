package com.loopers.infrastructure.product

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.config.jpa.QueryDslConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.like.Like
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
@Import(
    DataSourceConfig::class,
    QueryDslConfig::class,
    MySqlTestContainersConfig::class,
    BrandRepositoryImpl::class,
    ProductRepositoryImpl::class,
)
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
        listOf(first, second, third).forEach { shareCreatedAt(table = "product", id = it.id) }
        entityManager.clear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LATEST)

        assertThat(slice.items.map { it.id }).containsExactly(third.id, second.id, first.id)
    }

    /** 삭제된 브랜드를 가리키는 필터는 비어 있다. 브랜드가 살아 있지 않으면 그 아래 상품도 목록에 오르지 않는다. */

    /**
     * 좋아요를 가장 먼저 등록한 상품에 몰아 주어, 기준이 `latest`나 id 내림차순으로 새면 차례가 뒤집히게 한다.
     */
    @Test
    fun `findAll sorted by likes puts the most liked first`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val most = productRepository.save(product(brand))
        val fewest = productRepository.save(product(brand))
        val middling = productRepository.save(product(brand))
        entityManager.flushAndClear()
        likedBy(most, users = 3)
        likedBy(fewest, users = 1)
        likedBy(middling, users = 2)
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LIKES_DESC)

        assertThat(slice.items.map { it.id }).containsExactly(most.id, middling.id, fewest.id)
    }

    @Test
    fun `findAll sorted by likes breaks a tie with the later id first`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val first = productRepository.save(product(brand))
        val second = productRepository.save(product(brand))
        val third = productRepository.save(product(brand))
        entityManager.flushAndClear()
        listOf(first, second, third).forEach { likedBy(it, users = 2) }
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LIKES_DESC)

        assertThat(slice.items.map { it.id }).containsExactly(third.id, second.id, first.id)
    }

    /**
     * 좋아요가 하나도 없는 상품도 목록에 있고 끝에 온다. `left join`이 맞춰 줄 행을 찾지 못해도 상품은 남는다.
     *
     * 좋아요가 없는 상품을 좋아요 하나짜리보다 나중에 등록하는 까닭은, 세는 것이 관계 행이 아니라 결합된 행이면
     * (`count(*)`) 없는 쪽도 1로 세어져 둘이 동률이 되고 동률 규칙이 차례를 뒤집기 때문이다. 그래야 이 테스트가
     * 둘을 구별한다.
     */
    @Test
    fun `findAll sorted by likes keeps a product nobody liked, last`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val liked = productRepository.save(product(brand))
        val unliked = productRepository.save(product(brand))
        val mostLiked = productRepository.save(product(brand))
        entityManager.flushAndClear()
        likedBy(liked, users = 1)
        likedBy(mostLiked, users = 2)
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LIKES_DESC)

        assertThat(slice.items.map { it.id }).containsExactly(mostLiked.id, liked.id, unliked.id)
    }

    /**
     * 좋아요 많은순에도 브랜드 필터와 조각 나누기가 그대로 있다. 다른 브랜드에 좋아요가 가장 많은 상품을 두어,
     * 필터가 새면 그 상품이 맨 앞에 끼어들게 한다. `group by` 뒤에 `limit`이 붙는 자리이기도 하다.
     */
    @Test
    fun `findAll sorted by likes keeps the brand filter and slices with hasNext`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val other = brandRepository.save(Brand("나이키"))
        val fewest = productRepository.save(product(brand))
        val most = productRepository.save(product(brand))
        val middling = productRepository.save(product(brand))
        val othersMostLiked = productRepository.save(product(other))
        entityManager.flushAndClear()
        likedBy(fewest, users = 1)
        likedBy(most, users = 3)
        likedBy(middling, users = 2)
        likedBy(othersMostLiked, users = 9)
        entityManager.flushAndClear()

        val first = productRepository.findAll(brandId = brand.id, page = 0, size = 2, sort = ProductSort.LIKES_DESC)
        val second = productRepository.findAll(brandId = brand.id, page = 1, size = 2, sort = ProductSort.LIKES_DESC)

        assertAll(
            { assertThat(first.items.map { it.id }).containsExactly(most.id, middling.id) },
            { assertThat(first.hasNext).isTrue() },
            { assertThat(first.page).isZero() },
            { assertThat(first.size).isEqualTo(2) },
            { assertThat(second.items.map { it.id }).containsExactly(fewest.id) },
            { assertThat(second.hasNext).isFalse() },
            { assertThat(second.page).isEqualTo(1) },
        )
    }

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
        val active = productRepository.save(product(brand))
        productRepository.save(product(brand).apply { delete() })
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LATEST)

        assertThat(slice.items.map { it.id }).containsExactly(active.id)
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
     * fetch join이 `@ManyToOne(optional = false)`를 inner join으로 읽고 [Brand]의 `@SQLRestriction`이 그 join에도 붙으므로,
     * 삭제된 브랜드에 달렸지만 자신은 삭제되지 않은 상품은 목록에서 빠진다. 상품 자체는 그대로 있다. 이 조합은 브랜드 삭제 거절이
     * 막고 있어 실제로는 닿을 수 없다. 저장소는 그 거절을 모르므로 여기서만 만들 수 있다(설계 7).
     */
    @Test
    fun `findAll leaves out an active product whose brand was deleted`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val active = productRepository.save(product(brand))
        brand.delete()
        entityManager.flushAndClear()

        val slice = productRepository.findAll(brandId = null, page = 0, size = 20, sort = ProductSort.LATEST)

        assertAll(
            { assertThat(productRepository.findById(active.id)).isNotNull() },
            { assertThat(slice.items).isEmpty() },
        )
    }

    @Test
    fun `existsByBrandId is true while the brand has a product`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        productRepository.save(product(brand))
        entityManager.flushAndClear()

        assertThat(productRepository.existsByBrandId(brand.id)).isTrue()
    }

    /** 브랜드 삭제 조건이 기대는 사실이다. 삭제된 상품이 남은 상품으로 세어지면 그 브랜드는 영영 삭제할 수 없다. */
    @Test
    fun `existsByBrandId does not count deleted products`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        productRepository.save(product(brand).apply { delete() })
        entityManager.flushAndClear()

        assertThat(productRepository.existsByBrandId(brand.id)).isFalse()
    }

    @Test
    fun `existsByBrandId does not count another brand's products`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val other = brandRepository.save(Brand("나이키"))
        productRepository.save(product(other))
        entityManager.flushAndClear()

        assertThat(productRepository.existsByBrandId(brand.id)).isFalse()
    }

    @Test
    fun `existsByBrandId is false for an unknown brand`() {
        assertThat(productRepository.existsByBrandId(999L)).isFalse()
    }

    /**
     * 차례를 정하는 것은 상품을 등록한 시각이 아니라 좋아요를 누른 시각이다. 등록 차례와 누른 차례를 달리 두어
     * 어느 시각으로 줄을 세우는지가 드러나게 한다.
     */
    @Test
    fun `findAllLikedBy returns the products the user liked, the most recently liked first`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registeredFirst = productRepository.save(product(brand))
        val registeredSecond = productRepository.save(product(brand))
        val registeredThird = productRepository.save(product(brand))
        listOf(registeredSecond, registeredThird, registeredFirst).forEach { like(userId = 1L, productId = it.id) }
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = 1L, page = 0, size = 20)

        assertThat(slice.items.map { it.id })
            .containsExactly(registeredFirst.id, registeredThird.id, registeredSecond.id)
    }

    /**
     * 누른 시각이 같으면 나중에 누른 좋아요가 앞선다. 동률을 깨는 것은 좋아요의 식별자이고 상품의 것이 아니므로,
     * 등록 차례와 누른 차례를 어긋나게 두어 둘이 같은 답을 내지 않게 한다. 같은 차례로 누르면 상품 id 내림차순으로
     * 깨도 지나간다. 동률을 native 쿼리로 만드는 까닭은 상품 목록과 같다.
     */
    @Test
    fun `findAllLikedBy breaks a tie in the like time with the later like first`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val registeredFirst = productRepository.save(product(brand))
        val registeredSecond = productRepository.save(product(brand))
        val registeredThird = productRepository.save(product(brand))
        val likes = listOf(registeredThird, registeredFirst, registeredSecond)
            .map { like(userId = 1L, productId = it.id) }
        entityManager.flushAndClear()
        likes.forEach { shareCreatedAt(table = "likes", id = it.id) }
        entityManager.clear()

        val slice = productRepository.findAllLikedBy(userId = 1L, page = 0, size = 20)

        assertThat(slice.items.map { it.id })
            .containsExactly(registeredSecond.id, registeredFirst.id, registeredThird.id)
    }

    /** 삭제된 상품은 없는 상품이므로 남은 좋아요가 목록을 되살리지 않는다. 좋아요 행은 그대로 있다(ADR 0001). */
    @Test
    fun `findAllLikedBy leaves out deleted products`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val active = productRepository.save(product(brand))
        val deleted = productRepository.save(product(brand))
        like(userId = 1L, productId = active.id)
        like(userId = 1L, productId = deleted.id)
        deleted.delete()
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = 1L, page = 0, size = 20)

        assertAll(
            { assertThat(slice.items.map { it.id }).containsExactly(active.id) },
            { assertThat(slice.hasNext).isFalse() },
        )
    }

    @Test
    fun `findAllLikedBy leaves out the products another user liked`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val mine = productRepository.save(product(brand))
        val theirs = productRepository.save(product(brand))
        like(userId = 1L, productId = mine.id)
        like(userId = 2L, productId = theirs.id)
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = 1L, page = 0, size = 20)

        assertThat(slice.items.map { it.id }).containsExactly(mine.id)
    }

    @Test
    fun `findAllLikedBy leaves out a product the user never liked`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        val liked = productRepository.save(product(brand))
        productRepository.save(product(brand))
        like(userId = 1L, productId = liked.id)
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = 1L, page = 0, size = 20)

        assertThat(slice.items.map { it.id }).containsExactly(liked.id)
    }

    @Test
    fun `findAllLikedBy reports hasNext while a later slice remains`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        repeat(3) { like(userId = 1L, productId = productRepository.save(product(brand)).id) }
        entityManager.flushAndClear()

        val first = productRepository.findAllLikedBy(userId = 1L, page = 0, size = 2)
        val second = productRepository.findAllLikedBy(userId = 1L, page = 1, size = 2)

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

    @Test
    fun `findAllLikedBy is empty for a user without likes`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        like(userId = 1L, productId = productRepository.save(product(brand)).id)
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = 999L, page = 0, size = 20)

        assertAll(
            { assertThat(slice.items).isEmpty() },
            { assertThat(slice.hasNext).isFalse() },
        )
    }

    /** 브랜드 이름을 읽어야 하므로 좋아요 목록도 상품마다 브랜드를 따로 조회하지 않는다(설계 7). */
    @Test
    fun `findAllLikedBy reads the brand together with the product`() {
        val brand = brandRepository.save(Brand("루퍼스"))
        like(userId = 1L, productId = productRepository.save(product(brand)).id)
        entityManager.flushAndClear()

        val found = productRepository.findAllLikedBy(userId = 1L, page = 0, size = 20).items.single()

        assertThat(Hibernate.isInitialized(found.brand)).isTrue()
    }

    private fun product(brand: Brand, price: Long = 10_000, stock: Int = 1) =
        Product(brand = brand, name = "티셔츠", price = Money(price), stock = Stock(stock))

    /** 좋아요 관계 하나. 좋아요는 사용자와 상품을 식별자로만 가리키므로(설계 2) 사용자 행 없이 만든다. */
    private fun like(userId: Long, productId: Long): Like =
        Like(userId = userId, productId = productId).also { entityManager.persist(it) }

    /**
     * [users]명이 [product]를 좋아한다. 좋아요는 사용자를 식별자로만 가리키므로 `users` 행은 없어도 된다(설계 2).
     * 같은 사용자–상품 쌍은 하나뿐이라 사용자 식별자를 상품마다 1부터 새로 센다.
     */
    private fun likedBy(product: Product, users: Int) {
        (1..users).forEach { entityManager.persist(Like(userId = it.toLong(), productId = product.id)) }
    }

    /**
     * 생성 시각을 모든 행이 같은 값으로 갖게 한다. `created_at`은 `@Column(updatable = false)`지만
     * 그것은 JPA의 UPDATE만 막는 것이고 native 쿼리는 영속성 컨텍스트를 거치지 않는다.
     */
    private fun shareCreatedAt(table: String, id: Long) {
        entityManager
            .createNativeQuery("update $table set created_at = :at where id = :id")
            .setParameter("at", SHARED_CREATED_AT)
            .setParameter("id", id)
            .executeUpdate()
    }

    companion object {
        private const val SHARED_CREATED_AT = "2026-01-01 00:00:00.000000"
    }
}
