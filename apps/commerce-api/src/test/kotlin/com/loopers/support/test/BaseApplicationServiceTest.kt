package com.loopers.support.test

import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.like.provided.Liker
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.provided.OrderCreator
import com.loopers.application.point.provided.PointCharger
import com.loopers.application.point.required.PointAccountRepository
import com.loopers.application.product.provided.ProductRegister
import com.loopers.application.product.required.ProductRepository
import com.loopers.application.user.required.UserRepository
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.createBrandAdminRegisterRequest
import com.loopers.domain.like.Like
import com.loopers.domain.like.createLikeRequest
import com.loopers.domain.order.Order
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.product.Product
import com.loopers.domain.product.createProductAdminRegisterRequest
import com.loopers.domain.product.createProductAdminStockUpdateRequest
import com.loopers.domain.product.createProductAdminUpdateRequest
import com.loopers.domain.user.User
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

/**
 * provided 포트 테스트의 기반. 전체 컨텍스트를 실제 MySQL·Redis 위에 띄우고, 테스트 트랜잭션이 포트의 트랜잭션을 감싸
 * 테스트마다 롤백으로 정리한다. 추가 설정이 없어 [com.loopers.CommerceApiContextTest]와 컨텍스트를 나눠 쓴다.
 * 공통 설정 애노테이션은 모두 여기에 있다. 하위 클래스가 더하는 것은 추가 `@Import`와 트랜잭션에서 빠지는 표시뿐이다(ADR 0012).
 *
 * 데이터는 `prepare<Type>`과 변경 도우미로 준비한다. 포트가 만들 수 있는 상태는 그 조각의 provided 포트로 만든다.
 * 포트가 엔티티를 돌려주면 그대로 쓰고, `ProductInfo`를 돌려주는 상품만 엔티티를 ID로 다시 읽는다(ADR 0014).
 * 포트가 없는 데이터(사용자, 포인트 계정)와 포트가 막는 상태는 저장소에 엔티티를 저장해 만들고,
 * 포트가 막는 상태를 만드는 도우미는 우회를 이름에 드러낸다.
 * 마지막으로 준비한 엔티티는 타입마다 필드에 남고, 다른 `prepare`의 기본값으로 불린 `prepare`도 필드를 바꾼다.
 * 테스트가 필드를 읽어도 되는 경우는 `CODING_STANDARDS.md`의 Fixture 절에 있다.
 */
@SpringBootTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
@Transactional
abstract class BaseApplicationServiceTest {
    @Autowired
    protected lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var brandRegister: BrandRegister

    @Autowired
    private lateinit var brandRepository: BrandRepository

    @Autowired
    private lateinit var productRegister: ProductRegister

    @Autowired
    private lateinit var productRepository: ProductRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var pointAccountRepository: PointAccountRepository

    @Autowired
    private lateinit var pointCharger: PointCharger

    @Autowired
    private lateinit var liker: Liker

    @Autowired
    private lateinit var likeRepository: LikeRepository

    @Autowired
    private lateinit var orderCreator: OrderCreator

    @Autowired
    private lateinit var orderConfirmer: OrderConfirmer

    /** 마지막으로 준비한 브랜드. */
    protected lateinit var brand: Brand

    /** 마지막으로 준비한 상품. */
    protected lateinit var product: Product

    /** 마지막으로 준비한 사용자. */
    protected lateinit var user: User

    /** 마지막으로 준비한 좋아요. */
    protected lateinit var like: Like

    /** 마지막으로 준비한 포인트 계정. [prepareUser]가 사용자와 함께 준비하고, [prepareUserWithoutAccount]는 바꾸지 않는다. */
    protected lateinit var pointAccount: PointAccount

    /** 마지막으로 준비한 주문. */
    protected lateinit var order: Order

    protected fun prepareBrand(name: String? = null): Brand {
        return brandRegister.register(createBrandAdminRegisterRequest(name = name)).also { brand = it }
    }

    /** 포트가 [com.loopers.application.product.provided.ProductInfo]를 돌려주므로 엔티티는 ID로 다시 읽는다. */
    protected fun prepareProduct(
        brand: Brand = prepareBrand(),
        name: String? = null,
        price: Long? = null,
        stock: Int? = null,
    ): Product {
        val registered = productRegister.register(
            createProductAdminRegisterRequest(brand.id, name = name, price = price, stock = stock),
        )
        return productRepository.findById(registered.id)!!.also { product = it }
    }

    /** 사용자를 만드는 포트가 없어 저장소로 만든다. 처음 잔액이 0원인 포인트 계정도 함께 저장한다(설계 5.9). */
    protected fun prepareUser(): User {
        return userRepository.save(User()).also {
            pointAccount = pointAccountRepository.save(PointAccount(it.id))
            user = it
        }
    }

    /** 계정 없이 사용자만 저장한다. 사용자와 계정이 어긋난 데이터를 막는 테스트만 쓴다(설계 5.9, 6 끝). */
    protected fun prepareUserWithoutAccount(): User {
        return userRepository.save(User()).also { user = it }
    }

    /** [Liker]는 아무것도 돌려주지 않으므로 좋아요는 사용자와 상품으로 다시 읽는다. */
    protected fun prepareLike(
        user: User = prepareUser(),
        product: Product = prepareProduct(),
    ): Like {
        liker.like(userId = user.id, request = createLikeRequest(productId = product.id))
        return likeRepository.findByUserIdAndProductId(userId = user.id, productId = product.id)!!.also { like = it }
    }

    /**
     * 넘긴 상품마다 품목 하나를 담아 주문을 생성한다. 수량이 `null`이면 품목마다 fixture가 뽑는다.
     * 포트가 품목까지 담은 주문을 돌려주므로 다시 읽지 않는다.
     */
    protected fun prepareOrder(
        user: User = prepareUser(),
        products: List<Product> = listOf(prepareProduct()),
        quantity: Int? = null,
    ): Order {
        return orderCreator.create(user.id, createOrderCreateRequest(products.map { it.id }, quantity = quantity))
            .also { order = it }
    }

    /**
     * 품목마다 수량이 다른 주문. 상품과 수량의 짝마다 품목 하나를 담고, 수량이 `null`인 품목은 fixture가 뽑는다.
     * 짝이 `vararg`라 사용자는 뒤에 두고 이름으로 넘긴다(`prepareOrder(shirt to 2, user = owner)`).
     * 짝 없이 부르면(`prepareOrder()`, `prepareOrder(user = owner)`) Kotlin이 `vararg`가 아닌 위의 오버로드를 고른다.
     */
    protected fun prepareOrder(vararg items: Pair<Product, Int?>, user: User = prepareUser()): Order {
        val request = createOrderCreateRequest(*items.map { (product, quantity) -> product.id to quantity }.toTypedArray())
        return orderCreator.create(user.id, request).also { order = it }
    }

    protected fun deleteBrand(brand: Brand = this.brand) {
        brandRegister.delete(brand.id)
    }

    /**
     * 삭제되지 않은 상품을 남긴 채 브랜드를 삭제한다. 브랜드 삭제의 연쇄와 브랜드 잠금 때문에 포트로는 닿을 수 없는 상태라
     * 저장소로 만든다(ADR 0017, 0018). 읽기 쪽 브랜드 필터(목록의 inner join, 주문의 `findByIdWithActiveBrand`)를
     * 고정하려고 둔다. 테스트 트랜잭션 없이도 남도록 삭제 시각을 찍어 명시적으로 저장한다.
     */
    protected fun deleteBrandKeepingProducts(brand: Brand = this.brand) {
        brandRepository.save(brand.apply { delete() })
    }

    protected fun updateProduct(name: String? = null, price: Long? = null, product: Product = this.product) {
        productRegister.update(product.id, createProductAdminUpdateRequest(name = name, price = price))
    }

    /** 재고를 [quantity]개로 맞춘다. 빼거나 더하지 않는다. */
    protected fun updateProductStock(quantity: Int, product: Product = this.product) {
        productRegister.updateStock(product.id, createProductAdminStockUpdateRequest(quantity = quantity))
    }

    protected fun deleteProduct(product: Product = this.product) {
        productRegister.delete(product.id)
    }

    /** 사용자의 포인트 계정에 충전액만큼 충전한다. */
    protected fun charge(amount: Long, user: User = this.user) {
        pointCharger.charge(user.id, createPointChargeRequest(amount = amount))
    }

    /** 주문한 사용자가 주문을 확정한다. 품목의 재고와 그 사용자의 잔액이 넉넉해야 한다. */
    protected fun confirmOrder(order: Order = this.order) {
        orderConfirmer.confirm(order.userId, order.id)
    }
}
