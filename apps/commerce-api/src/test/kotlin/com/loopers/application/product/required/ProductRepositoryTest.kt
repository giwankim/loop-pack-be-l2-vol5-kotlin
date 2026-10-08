package com.loopers.application.product.required

import com.loopers.adapter.persistence.product.QuerydslProductListRepository
import com.loopers.application.product.provided.ProductSort
import com.loopers.config.jpa.QueryDslConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.product.Product
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest

/**
 * Spring Data가 만든 [ProductRepository]와 QueryDSL로 짠 [ProductListRepository]가 실제 MySQL에서 계약을 지키는지 확인한다.
 * 패키지 위치의 이유는 [com.loopers.application.user.required.UserRepositoryTest]와 같다. 슬라이스는 `@Component`를
 * 스캔하지 않으므로 목록의 구현 [QuerydslProductListRepository]와 그것이 쓰는 QueryDSL 설정은 직접 가져온다. 부르는 것은 포트다.
 *
 * 좋아요 목록 [ProductRepository.findAllLikedBy]는 Spring Data가 `@Query`로 만드는 조회이고 그 결과가 곧 포트의 조각이다.
 * 경계 사례(크기와 같을 때, 하나 더 있을 때, `page = 1`의 offset)는 Spring Data의 `Slice`가 내주는 값으로 본다.
 */
@Import(QueryDslConfig::class, QuerydslProductListRepository::class)
class ProductRepositoryTest(
    private val productRepository: ProductRepository,
    private val productListRepository: ProductListRepository,
) : BaseRepositoryTest() {
    @Test
    fun `findById reads a saved product back with its brand, price, and stock after flush and clear`() {
        prepareBrand(name = "루퍼스")
        prepareProduct(brand, name = "티셔츠", price = Money(12_000), stock = 7)
        entityManager.flushAndClear()

        val found = productRepository.findById(product.id)

        assertThat(found).isNotNull().isNotSameAs(product)
        assertThat(found?.id).isEqualTo(product.id)
        assertThat(found?.brand?.id).isEqualTo(brand.id)
        assertThat(found?.brand?.name).isEqualTo("루퍼스")
        assertThat(found?.name).isEqualTo("티셔츠")
        assertThat(found?.price).isEqualTo(Money(12_000))
        assertThat(found?.stock).isEqualTo(7)
        assertThat(found?.createdAt).isNotNull()
        assertThat(found?.updatedAt).isNotNull()
        assertThat(found?.deletedAt).isNull()
    }

    @Test
    fun `findById leaves the brand as an uninitialized proxy until a field other than id is read`() {
        prepareBrand(name = "루퍼스")
        prepareProduct(brand)
        entityManager.flushAndClear()

        val found = productRepository.findById(product.id)!!

        // 엔티티와 BaseEntity 가 allOpen 으로 열려 있어야 Hibernate 가 Brand 서브클래스 프록시를 만든다
        // (build-logic 의 loopers.jpa 컨벤션). 하나라도 final 이면 HHH000305 를 남기고 곧바로 조회한다.
        assertThat(Hibernate.isInitialized(found.brand)).isFalse()
        // 식별자는 프록시가 들고 있으므로 읽어도 초기화되지 않는다.
        assertThat(found.brand.id).isEqualTo(brand.id)
        assertThat(Hibernate.isInitialized(found.brand)).isFalse()
        // 다른 필드를 읽는 순간 브랜드를 조회한다.
        assertThat(found.brand.name).isEqualTo("루퍼스")
        assertThat(Hibernate.isInitialized(found.brand)).isTrue()
    }

    @Test
    fun `save stores price and stock in the price and stock columns`() {
        prepareBrand()
        val saved = productRepository.save(createProduct(brand, price = Money(12_000), stock = 7))
        entityManager.flushAndClear()

        val row = entityManager
            .createNativeQuery("select brand_id, price, stock from product where id = :id")
            .setParameter("id", saved.id)
            .singleResult as Array<*>

        assertThat((row[0] as Number).toLong()).isEqualTo(brand.id)
        assertThat((row[1] as Number).toLong()).isEqualTo(12_000L)
        assertThat((row[2] as Number).toInt()).isEqualTo(7)
    }

    @Test
    fun `findById returns null for a deleted product`() {
        prepareProduct()
        deleteProduct()
        entityManager.flushAndClear()

        val found = productRepository.findById(product.id)

        assertThat(found).isNull()
    }

    @Test
    fun `findById returns null for an unknown id`() {
        assertThat(productRepository.findById(999L)).isNull()
    }

    /** 주문은 이 조회를 받은 뒤 트랜잭션 밖에서 브랜드를 건너도 지연 로딩에 닿지 않아야 한다. */
    @Test
    fun `findByIdWithActiveBrand reads the brand together with the product`() {
        prepareProduct()
        entityManager.flushAndClear()

        val found = productRepository.findByIdWithActiveBrand(product.id)!!

        assertThat(found.id).isEqualTo(product.id)
        assertThat(Hibernate.isInitialized(found.brand)).isTrue()
    }

    /** [ProductRepository.findById]는 브랜드의 삭제를 보지 않으므로, 같은 상품을 두 조회가 다르게 답하는 것을 함께 본다. */
    @Test
    fun `findByIdWithActiveBrand returns null for a deleted product and for an active product whose brand was deleted`() {
        val deleted = prepareProduct()
        deleteProduct(deleted)
        val closedBrand = prepareBrand()
        val ofClosedBrand = prepareProduct(closedBrand)
        deleteBrandKeepingProducts(closedBrand)
        entityManager.flushAndClear()

        assertThat(productRepository.findByIdWithActiveBrand(deleted.id)).isNull()
        assertThat(productRepository.findById(ofClosedBrand.id)).isNotNull()
        assertThat(productRepository.findByIdWithActiveBrand(ofClosedBrand.id)).isNull()
    }

    @Test
    fun `findAll returns the products of every brand, latest registered first`() {
        val first = prepareProduct()
        val second = prepareProduct()
        val third = prepareProduct(first.brand)
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.LATEST)

        assertThat(slice.content.map { it.id }).containsExactly(third.id, second.id, first.id)
    }

    @Test
    fun `findAll sorted by price puts the cheapest first`() {
        prepareBrand()
        val dear = prepareProduct(brand, price = Money(30_000))
        val cheap = prepareProduct(brand, price = Money(10_000))
        val middling = prepareProduct(brand, price = Money(20_000))
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.PRICE_ASC)

        assertThat(slice.content.map { it.id }).containsExactly(cheap.id, middling.id, dear.id)
    }

    @Test
    fun `findAll sorted by price breaks a tie with the later id first`() {
        prepareBrand()
        val first = prepareProduct(brand, price = Money(10_000))
        val second = prepareProduct(brand, price = Money(10_000))
        val third = prepareProduct(brand, price = Money(10_000))
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.PRICE_ASC)

        assertThat(slice.content.map { it.id }).containsExactly(third.id, second.id, first.id)
    }

    /**
     * 등록 시각이 같은 상품은 나중에 받은 식별자가 앞선다. Hibernate가 만드는 `created_at`은 `datetime(6)`이라
     * 이어서 저장해도 시각이 저절로 같아지지는 않으므로, 동률을 native 쿼리로 만들어 고정한다.
     */
    @Test
    fun `findAll sorted by latest breaks a tie with the later id first`() {
        prepareBrand()
        val first = prepareProduct(brand)
        val second = prepareProduct(brand)
        val third = prepareProduct(brand)
        entityManager.flushAndClear()
        listOf(first, second, third).forEach { shareCreatedAt(table = "product", id = it.id) }
        entityManager.clear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.LATEST)

        assertThat(slice.content.map { it.id }).containsExactly(third.id, second.id, first.id)
    }

    /**
     * 좋아요를 가장 먼저 등록한 상품에 몰아 주어, 기준이 `latest`나 id 내림차순으로 새면 차례가 뒤집히게 한다.
     */
    @Test
    fun `findAll sorted by likes puts the most liked first`() {
        prepareBrand()
        val most = prepareProduct(brand)
        val fewest = prepareProduct(brand)
        val middling = prepareProduct(brand)
        entityManager.flushAndClear()
        likedBy(most, users = 3)
        likedBy(fewest, users = 1)
        likedBy(middling, users = 2)
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.LIKES_DESC)

        assertThat(slice.content.map { it.id }).containsExactly(most.id, middling.id, fewest.id)
        // group by가 붙는 유일한 기준이라 브랜드를 함께 읽는 일이 여기서만 깨질 수 있다.
        // 프록시로 남으면 항목마다 조회가 붙고, 트랜잭션 밖에서는 아예 읽히지 않는다(설계 5.7).
        assertThat(slice.content).allSatisfy { assertThat(Hibernate.isInitialized(it.brand)).isTrue() }
    }

    /**
     * 좋아요 수가 같을 때 차례를 정하는 것이 id임을 본다. 등록 시각까지 같게 맞추지 않으면 동률 규칙이
     * `createdAt` 내림차순으로 새도 id 차례와 겹쳐 이 테스트가 지나간다(`latest`의 동률 테스트와 같은 요령).
     */
    @Test
    fun `findAll sorted by likes breaks a tie with the later id first`() {
        prepareBrand()
        val first = prepareProduct(brand)
        val second = prepareProduct(brand)
        val third = prepareProduct(brand)
        entityManager.flushAndClear()
        listOf(first, second, third).forEach { likedBy(it, users = 2) }
        entityManager.flushAndClear()
        listOf(first, second, third).forEach { shareCreatedAt(table = "product", id = it.id) }
        entityManager.clear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.LIKES_DESC)

        assertThat(slice.content.map { it.id }).containsExactly(third.id, second.id, first.id)
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
        prepareBrand()
        val liked = prepareProduct(brand)
        val unliked = prepareProduct(brand)
        val mostLiked = prepareProduct(brand)
        entityManager.flushAndClear()
        likedBy(liked, users = 1)
        likedBy(mostLiked, users = 2)
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.LIKES_DESC)

        assertThat(slice.content.map { it.id }).containsExactly(mostLiked.id, liked.id, unliked.id)
    }

    /**
     * 좋아요 많은순에도 브랜드 필터와 조각 나누기가 그대로 있다. 다른 브랜드에 좋아요가 가장 많은 상품을 두어,
     * 필터가 새면 그 상품이 맨 앞에 끼어들게 한다. `group by` 뒤에 `limit`이 붙는 자리이기도 하다.
     */
    @Test
    fun `findAll sorted by likes keeps the brand filter and slices with hasNext`() {
        val asked = prepareBrand()
        val other = prepareBrand()
        val fewest = prepareProduct(asked)
        val most = prepareProduct(asked)
        val middling = prepareProduct(asked)
        val othersMostLiked = prepareProduct(other)
        entityManager.flushAndClear()
        likedBy(fewest, users = 1)
        likedBy(most, users = 3)
        likedBy(middling, users = 2)
        likedBy(othersMostLiked, users = 9)
        entityManager.flushAndClear()

        val first =
            productListRepository.findAll(brandId = asked.id, pageable = PageRequest.of(0, 2), sort = ProductSort.LIKES_DESC)
        val second =
            productListRepository.findAll(brandId = asked.id, pageable = PageRequest.of(1, 2), sort = ProductSort.LIKES_DESC)

        assertThat(first.content.map { it.id }).containsExactly(most.id, middling.id)
        assertThat(first.hasNext()).isTrue()
        assertThat(first.number).isZero()
        assertThat(first.size).isEqualTo(2)
        assertThat(second.content.map { it.id }).containsExactly(fewest.id)
        assertThat(second.hasNext()).isFalse()
        assertThat(second.number).isEqualTo(1)
        assertThat(second.size).isEqualTo(2)
    }

    /** 삭제된 브랜드를 가리키는 필터는 비어 있다. 브랜드가 살아 있지 않으면 그 아래 상품도 목록에 오르지 않는다. */
    @Test
    fun `findAll with a deleted brand's id is empty`() {
        prepareProduct()
        deleteBrandKeepingProducts()
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = brand.id, pageable = PageRequest.of(0, 20), sort = ProductSort.LATEST)

        assertThat(slice.content).isEmpty()
        assertThat(slice.hasNext()).isFalse()
    }

    @Test
    fun `findAll leaves out deleted products`() {
        prepareBrand()
        val active = prepareProduct(brand)
        deleteProduct(prepareProduct(brand))
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.LATEST)

        assertThat(slice.content.map { it.id }).containsExactly(active.id)
    }

    @Test
    fun `findAll with a brandId keeps only that brand's products`() {
        val asked = prepareBrand()
        val other = prepareBrand()
        val mine = prepareProduct(asked)
        prepareProduct(other)
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = asked.id, pageable = PageRequest.of(0, 20), sort = ProductSort.LATEST)

        assertThat(slice.content.map { it.id }).containsExactly(mine.id)
    }

    @Test
    fun `findAll with an unknown brandId is empty`() {
        prepareProduct()
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = 999L, pageable = PageRequest.of(0, 20), sort = ProductSort.LATEST)

        assertThat(slice.content).isEmpty()
        assertThat(slice.hasNext()).isFalse()
    }

    @Test
    fun `findAll reports hasNext while a later slice remains`() {
        prepareBrand()
        repeat(3) { prepareProduct(brand) }
        entityManager.flushAndClear()

        val first = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 2), sort = ProductSort.LATEST)
        val second = productListRepository.findAll(brandId = null, pageable = PageRequest.of(1, 2), sort = ProductSort.LATEST)

        assertThat(first.content).hasSize(2)
        assertThat(first.hasNext()).isTrue()
        assertThat(first.number).isZero()
        assertThat(first.size).isEqualTo(2)
        assertThat(second.content).hasSize(1)
        assertThat(second.hasNext()).isFalse()
        assertThat(second.number).isEqualTo(1)
        assertThat(second.size).isEqualTo(2)
    }

    @Test
    fun `findAll has no next slice when the products fill the page exactly`() {
        prepareBrand()
        repeat(2) { prepareProduct(brand) }
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 2), sort = ProductSort.LATEST)

        assertThat(slice.content).hasSize(2)
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(2)
    }

    /**
     * fetch join이 `@ManyToOne(optional = false)`를 inner join으로 읽고 [Brand]의 `@SQLRestriction`이 그 join에도 붙으므로,
     * 삭제된 브랜드에 달렸지만 자신은 삭제되지 않은 상품은 목록에서 빠진다. 상품 자체는 그대로 있다. 이 조합은 브랜드 삭제 거절이
     * 막고 있어 실제로는 닿을 수 없다. 저장소는 그 거절을 모르므로 여기서만 만들 수 있다(설계 7).
     */
    @Test
    fun `findAll leaves out an active product whose brand was deleted`() {
        prepareProduct()
        deleteBrandKeepingProducts()
        entityManager.flushAndClear()

        val slice = productListRepository.findAll(brandId = null, pageable = PageRequest.of(0, 20), sort = ProductSort.LATEST)

        assertThat(productRepository.findById(product.id)).isNotNull()
        assertThat(slice.content).isEmpty()
    }

    @Test
    fun `existsByBrandId is true while the brand has a product`() {
        prepareProduct()
        entityManager.flushAndClear()

        assertThat(productRepository.existsByBrandId(brand.id)).isTrue()
    }

    /** 브랜드 삭제 조건이 기대는 사실이다. 삭제된 상품이 남은 상품으로 세어지면 그 브랜드는 영영 삭제할 수 없다. */
    @Test
    fun `existsByBrandId does not count deleted products`() {
        prepareProduct()
        deleteProduct()
        entityManager.flushAndClear()

        assertThat(productRepository.existsByBrandId(brand.id)).isFalse()
    }

    @Test
    fun `existsByBrandId does not count another brand's products`() {
        val asked = prepareBrand()
        val other = prepareBrand()
        prepareProduct(other)
        entityManager.flushAndClear()

        assertThat(productRepository.existsByBrandId(asked.id)).isFalse()
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
        prepareBrand()
        val registeredFirst = prepareProduct(brand)
        val registeredSecond = prepareProduct(brand)
        val registeredThird = prepareProduct(brand)
        prepareUser()
        listOf(registeredSecond, registeredThird, registeredFirst).forEach { prepareLike(user, it) }
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = user.id, pageable = PageRequest.of(0, 20))

        assertThat(slice.content.map { it.id })
            .containsExactly(registeredFirst.id, registeredThird.id, registeredSecond.id)
    }

    /**
     * 누른 시각이 같으면 나중에 누른 좋아요가 앞선다. 동률을 깨는 것은 좋아요의 식별자이고 상품의 것이 아니므로,
     * 등록 차례와 누른 차례를 어긋나게 두어 둘이 같은 답을 내지 않게 한다. 같은 차례로 누르면 상품 id 내림차순으로
     * 깨도 지나간다. 동률을 native 쿼리로 만드는 까닭은 상품 목록과 같다.
     */
    @Test
    fun `findAllLikedBy breaks a tie in the like time with the later like first`() {
        prepareBrand()
        val registeredFirst = prepareProduct(brand)
        val registeredSecond = prepareProduct(brand)
        val registeredThird = prepareProduct(brand)
        prepareUser()
        val likes = listOf(registeredThird, registeredFirst, registeredSecond)
            .map { prepareLike(user, it) }
        entityManager.flushAndClear()
        likes.forEach { shareCreatedAt(table = "likes", id = it.id) }
        entityManager.clear()

        val slice = productRepository.findAllLikedBy(userId = user.id, pageable = PageRequest.of(0, 20))

        assertThat(slice.content.map { it.id })
            .containsExactly(registeredSecond.id, registeredFirst.id, registeredThird.id)
    }

    /** 삭제된 상품은 없는 상품이므로 남은 좋아요가 목록을 되살리지 않는다. 좋아요 행은 그대로 있다(ADR 0001). */
    @Test
    fun `findAllLikedBy leaves out deleted products`() {
        prepareBrand()
        val active = prepareProduct(brand)
        val deleted = prepareProduct(brand)
        prepareUser()
        prepareLike(user, active)
        prepareLike(user, deleted)
        deleteProduct(deleted)
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = user.id, pageable = PageRequest.of(0, 20))

        assertThat(slice.content.map { it.id }).containsExactly(active.id)
        assertThat(slice.hasNext()).isFalse()
    }

    @Test
    fun `findAllLikedBy leaves out the products another user liked`() {
        prepareBrand()
        val mine = prepareProduct(brand)
        val theirs = prepareProduct(brand)
        val me = prepareUser()
        prepareLike(me, mine)
        prepareLike(product = theirs)
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = me.id, pageable = PageRequest.of(0, 20))

        assertThat(slice.content.map { it.id }).containsExactly(mine.id)
    }

    @Test
    fun `findAllLikedBy leaves out a product the user never liked`() {
        prepareBrand()
        val liked = prepareProduct(brand)
        prepareProduct(brand)
        prepareLike(product = liked)
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = user.id, pageable = PageRequest.of(0, 20))

        assertThat(slice.content.map { it.id }).containsExactly(liked.id)
    }

    @Test
    fun `findAllLikedBy reports hasNext while a later slice remains`() {
        prepareBrand()
        prepareUser()
        repeat(3) { prepareLike(user, prepareProduct(brand)) }
        entityManager.flushAndClear()

        val first = productRepository.findAllLikedBy(userId = user.id, pageable = PageRequest.of(0, 2))
        val second = productRepository.findAllLikedBy(userId = user.id, pageable = PageRequest.of(1, 2))

        assertThat(first.content).hasSize(2)
        assertThat(first.hasNext()).isTrue()
        assertThat(first.number).isZero()
        assertThat(first.size).isEqualTo(2)
        assertThat(second.content).hasSize(1)
        assertThat(second.hasNext()).isFalse()
        assertThat(second.number).isEqualTo(1)
        assertThat(second.size).isEqualTo(2)
    }

    @Test
    fun `findAllLikedBy has no next slice when the liked products fill the page exactly`() {
        prepareBrand()
        prepareUser()
        repeat(2) { prepareLike(user, prepareProduct(brand)) }
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = user.id, pageable = PageRequest.of(0, 2))

        assertThat(slice.content).hasSize(2)
        assertThat(slice.hasNext()).isFalse()
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(2)
    }

    @Test
    fun `findAllLikedBy is empty for a user without likes`() {
        prepareLike()
        entityManager.flushAndClear()

        val slice = productRepository.findAllLikedBy(userId = 999L, pageable = PageRequest.of(0, 20))

        assertThat(slice.content).isEmpty()
        assertThat(slice.hasNext()).isFalse()
    }

    /** 브랜드 이름을 읽어야 하므로 좋아요 목록도 상품마다 브랜드를 따로 조회하지 않는다(설계 7). */
    @Test
    fun `findAllLikedBy reads the brand together with the product`() {
        prepareLike()
        entityManager.flushAndClear()

        val found = productRepository.findAllLikedBy(userId = user.id, pageable = PageRequest.of(0, 20)).content.single()

        assertThat(Hibernate.isInitialized(found.brand)).isTrue()
    }

    /** [users]명이 [product]를 좋아한다. 같은 사용자–상품 쌍은 하나뿐이라 좋아요마다 사용자를 새로 준비한다. */
    private fun likedBy(product: Product, users: Int) {
        repeat(users) { prepareLike(product = product) }
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
