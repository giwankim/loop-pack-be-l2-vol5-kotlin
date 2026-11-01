package com.loopers.support.test

import com.loopers.adapter.integration.pg.LocalPgGateway
import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.like.provided.Liker
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.provided.OrderCreator
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.order.required.OrderRepository
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
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.product.Product
import com.loopers.domain.product.createProductAdminRegisterRequest
import com.loopers.domain.product.createProductAdminStockUpdateRequest
import com.loopers.domain.product.createProductAdminUpdateRequest
import com.loopers.domain.user.User
import com.loopers.support.RedisCleanUp
import com.loopers.support.SqlCaptureConfig
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TestPgOrderIdGenerator
import com.loopers.testcontainers.KafkaTestContainersConfig
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * provided 포트 테스트의 기반. 전체 컨텍스트를 실제 MySQL·Redis·Kafka 위에 띄우고, 테스트 트랜잭션이 포트의 트랜잭션을 감싸
 * 테스트마다 롤백으로 정리한다. `@Import`가 [com.loopers.CommerceApiContextTest]와 같아 그 컨텍스트를 나눠 쓴다.
 * [SqlCaptureConfig]는 `com.loopers.support`의 `captureSql`이 블록 안의 SQL을 잡게 한다. 블록 밖에서는 아무것도 하지 않는다.
 * 공통 설정 애노테이션은 모두 여기에 있다. 하위 클래스가 더하는 것은 추가 `@Import`, 트랜잭션에서 빠지는 표시, Service나 저장소,
 * 캐시 writer, Kafka 템플릿을 spy하는 `@MockkSpyBean` 필드, 누락 구간 테스트가 Kafka 템플릿을 바꾸는 `@MockkBean` 필드,
 * 측정용 seed를 클래스마다 한 번 넣는 `ProductQueryPerformanceApiTest`의 `@TestInstance(PER_CLASS)`,
 * 주문을 확정해 PG를 부르는 클래스의 `@EnableTestPg`, 주문 이벤트 토픽의 이름을 덮는 `@TestPropertySource`뿐이다(ADR 0012).
 *
 * Every context here needs the Kafka broker, not only the tests that read it: the application declares its order events topic,
 * and in the test profile the admin fails the context when it has no broker. [KafkaTestContainersConfig] starts the broker once
 * per JVM, and since every base that starts a full context imports it, it splits no cached context.
 *
 * 데이터는 `prepare<Type>`과 변경 도우미로 준비한다. 포트가 만들 수 있는 상태는 그 슬라이스의 provided 포트로 만든다.
 * 포트가 엔티티를 돌려주면 그대로 쓰고, `ProductInfo`를 돌려주는 상품만 엔티티를 ID로 다시 읽는다(ADR 0014).
 * 포트가 없는 데이터(사용자, 포인트 계정)와 포트가 막는 상태는 저장소에 엔티티를 저장해 만들고,
 * 포트가 막는 상태를 만드는 도우미는 우회를 이름에 드러낸다.
 * 마지막으로 준비한 엔티티는 타입마다 필드에 남고, 다른 `prepare`의 기본값으로 불린 `prepare`도 필드를 바꾼다.
 * 테스트가 필드를 읽어도 되는 경우는 `CODING_STANDARDS.md`의 Fixture 절에 있다.
 *
 * 테스트 PG를 쓰는 클래스는 [nextPgOrderId]로 결제가 받을 PG 주문 번호를 정한다. 쓰지 않고 남은 번호는 테스트가 끝날 때마다 버린다.
 *
 * 테스트가 시작할 때마다 PG 조회의 서킷 브레이커 `pg-lookup`을 CLOSED로 되돌리고, `@EnableTestPg`가 넣은 시계도 되돌린다(ADR 0030 규칙 8).
 * 브레이커는 컨텍스트의 빈이라 같은 컨텍스트를 쓰는 테스트끼리 이어진다. 되돌리지 않으면 앞 테스트의 조회 실패가 뒤 테스트의 조회를 막는다.
 *
 * 테스트가 끝날 때마다 [RedisCleanUp]으로 테스트 Redis를 비운다. 이것을 넓힌 [BaseWebApiAdapterTest]와 [BaseCommittingApplicationServiceTest]도
 * 물려받는다. Redis를 가져오지 않는 [BaseRepositoryTest]는 비우지 않는다. 고객 목록의 캐시 키는 상품 목록
 * revision을 품는데(ADR 0020), 롤백하는 테스트는 revision을 올리지 않고 커밋하는 테스트는 끝에 revision 표를 비우므로
 * 테스트마다 revision이 0에서 시작한다. Redis를 비우지 않으면 뒤 테스트가 같은 키에서 앞 테스트의 목록을 읽는다.
 * 비우는 것은 JVM마다 띄우는 테스트 컨테이너다. 공유 Redis가 아니다.
 */
@SpringBootTest
@Import(
    MySqlTestContainersConfig::class,
    RedisTestContainersConfig::class,
    KafkaTestContainersConfig::class,
    SqlCaptureConfig::class,
)
@Transactional
abstract class BaseApplicationServiceTest {
    @Autowired
    protected lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var redisCleanUp: RedisCleanUp

    @Autowired
    private lateinit var brandRegister: BrandRegister

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

    @Autowired
    private lateinit var orderRepository: OrderRepository

    /** `@EnableTestPg`가 가져온 컨텍스트에만 있다. 테스트 PG를 쓰지 않는 컨텍스트에서는 비어 있다. */
    @Autowired
    private lateinit var pgOrderIdGenerators: ObjectProvider<TestPgOrderIdGenerator>

    @Autowired
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    /**
     * The context's clock: the PG lookup breaker waits by it and an order is confirmed at its instant. Only contexts that
     * `@EnableTestPg` imports have one.
     */
    @Autowired
    private lateinit var clocks: ObjectProvider<TestClock>

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

    /** 앞 테스트가 연 브레이커와 옮긴 시계가 컨텍스트를 나눠 쓰는 이 테스트에 남지 않게 한다. */
    @BeforeEach
    fun resetClockAndPgLookupBreaker() {
        clocks.ifAvailable { it.reset() }
        circuitBreakerRegistry.circuitBreaker(LocalPgGateway.PG_LOOKUP).reset()
    }

    @AfterEach
    fun cleanUpRedis() {
        redisCleanUp.truncateAll()
    }

    /** 테스트가 정해 두고 쓰지 않은 PG 주문 번호가 다음 테스트의 결제에 가지 않게 한다. */
    @AfterEach
    fun clearPgOrderIds() {
        pgOrderIdGenerators.ifAvailable { it.clear() }
    }

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

    /**
     * 결제를 만들지 않고 [order]의 결제를 시작해, 결제수단이 PG인데 결제가 없는 결제 중인 주문으로 둔다. 확정은 결제와 함께만 결제를 시작하므로
     * 이 데이터의 불일치는 포트로 만들 수 없어 저장소로 만들고, 바꾼 주문을 명시적으로 저장한다. 주문 조회가 `PAYMENT_MISSING`인 장면만 쓴다(ADR 0028 규칙 1).
     */
    protected fun startPaymentWithoutPayment(order: Order = this.order) {
        orderRepository.save(order.apply { startPayment() })
    }

    protected fun deleteBrand(brand: Brand = this.brand) {
        brandRegister.delete(brand.id)
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

    /**
     * 다음에 시작하는 결제가 받을 PG 주문 번호를 정해 돌려준다. 테스트 PG의 생성 스텁에 넣는다(ADR 0029 규칙 4).
     * 결제마다 하나씩 정하고, 정한 차례대로 결제가 가져간다. 클래스에 `@EnableTestPg`가 있어야 한다.
     */
    protected fun nextPgOrderId(): String {
        return pgOrderIdGenerators.getObject().next()
    }

    /**
     * 다음 결제가 받을 PG 주문 번호를 정하고, 테스트 PG [testPg]가 그 번호와 [card]로 온 [amount]원 생성을 받아들여 PG 상태 [pgStatus]의
     * PG 거래로 답하게 한다. 거래 키는 매번 새로 뽑는다. 거래 키를 단언하는 테스트는 `TestPg.acceptsCreate`를 직접 부른다.
     */
    protected fun acceptNextCreate(testPg: TestPg, amount: Long, card: CreditCard, pgStatus: String = "SUCCESS") {
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = amount,
            card = card,
            transactionKey = UUID.randomUUID().toString(),
            pgStatus = pgStatus,
        )
    }

    /**
     * 주문한 사용자가 [order]를 확정하고, 테스트 PG [testPg]는 그 생성을 PG 상태 [pgStatus]의 PG 거래로 받아들인다. 확정의 결과를 돌려준다.
     * 성공이면 확정된 주문, 처리 중(`PENDING`)이면 결제 중인 주문이다. 품목의 재고가 넉넉해야 하고, 클래스에 `@EnableTestPg`가 있어야 한다.
     * 확정은 트랜잭션 밖에서만 돌므로(ADR 0028 규칙 2) 테스트 트랜잭션에서 빠진 클래스만 부른다. 커밋하는 기반과 `NOT_SUPPORTED`를 단 MockMvc 클래스다.
     */
    protected fun confirmOrder(testPg: TestPg, order: Order = this.order, pgStatus: String = "SUCCESS"): OrderInfo {
        val request = createOrderConfirmRequest()
        acceptNextCreate(
            testPg,
            amount = order.totalAmount.amount.longValueExact(),
            card = request.toCreditCard(),
            pgStatus = pgStatus,
        )

        return orderConfirmer.confirm(order.userId, order.id, idempotencyKey = UUID.randomUUID().toString(), request = request)
    }
}
