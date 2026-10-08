package com.loopers.support.test

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.like.required.LikeRepository
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.point.required.PointAccountRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.application.user.required.UserRepository
import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.createBrand
import com.loopers.domain.like.Like
import com.loopers.domain.order.Order
import com.loopers.domain.order.createOrder
import com.loopers.domain.order.createOrderProduct
import com.loopers.domain.point.PointAccount
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.testcontainers.MySqlTestContainersConfig
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import

/**
 * 저장소(required 포트) 테스트의 기반. JPA 슬라이스를 실제 MySQL 위에 띄우고, 테스트마다 트랜잭션이 롤백되어 정리가 필요 없다.
 * 공통 설정 애노테이션은 모두 여기에 있다. 하위 클래스가 더하는 것은 추가 `@Import`뿐이다(ADR 0012).
 *
 * `replace = NONE`이 없으면 Boot 4.1.1의 기본값 `NON_TEST`가 [DataSourceConfig]가 직접 만든 `HikariDataSource`를 내장 DB로
 * 바꾸려 한다. 내장 DB가 클래스 경로에 없어 컨텍스트가 뜨지 않는다.
 *
 * 슬라이스는 `@Component`를 스캔하지 않으므로 QueryDSL로 짠 저장소를 시험하는 클래스는 `QueryDslConfig`와 그 어댑터를 스스로
 * 가져온다. 여기에 두면 그것을 쓰지 않는 저장소 테스트의 컨텍스트 캐시 키가 바뀐다.
 *
 * 컨텍스트에 포트가 없으므로 `prepare<Type>`과 변경 도우미는 저장소에 엔티티 fixture를 저장한다.
 * 이름과 필드의 규칙은 [BaseApplicationServiceTest]와 같다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig::class, MySqlTestContainersConfig::class)
abstract class BaseRepositoryTest {
    @Autowired
    protected lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var brandRepository: BrandRepository

    @Autowired
    private lateinit var productRepository: ProductRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var pointAccountRepository: PointAccountRepository

    @Autowired
    private lateinit var likeRepository: LikeRepository

    @Autowired
    private lateinit var orderRepository: OrderRepository

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

    protected fun prepareBrand(name: String? = null): Brand =
        brandRepository.save(createBrand(name = name)).also { brand = it }

    protected fun prepareProduct(
        brand: Brand = prepareBrand(),
        name: String? = null,
        price: Money? = null,
        stock: Stock? = null,
    ): Product =
        productRepository.save(createProduct(brand, name = name, price = price, stock = stock)).also { product = it }

    /** 처음 잔액이 0원인 포인트 계정도 함께 저장한다. 이름이 같은 [BaseApplicationServiceTest.prepareUser]와 같은 상태다. */
    protected fun prepareUser(): User =
        userRepository.save(User()).also {
            pointAccount = pointAccountRepository.save(PointAccount(it.id))
            user = it
        }

    /** 계정 없이 사용자만 저장한다. 사용자와 계정이 어긋난 데이터를 만들 때만 쓴다. */
    protected fun prepareUserWithoutAccount(): User = userRepository.save(User()).also { user = it }

    protected fun prepareLike(
        user: User = prepareUser(),
        product: Product = prepareProduct(),
    ): Like =
        likeRepository.save(Like(userId = user.id, productId = product.id)).also { like = it }

    /** 넘긴 상품마다 그 상품을 스냅숏한 품목 하나를 담는다. 품목의 상품 ID가 외래 키에 걸리므로 저장한 상품을 받는다. */
    protected fun prepareOrder(
        user: User = prepareUser(),
        products: List<Product> = listOf(prepareProduct()),
    ): Order =
        orderRepository.save(createOrder(user.id, products.map { createOrderProduct(it) })).also { order = it }

    /** 삭제 시각을 찍어 명시적으로 저장한다. 변경 감지에 기대지 않는다. */
    protected fun deleteBrand(brand: Brand = this.brand) {
        brandRepository.save(brand.apply { delete() })
    }

    /**
     * 살아 있는 상품을 남긴 채 브랜드를 삭제한다. 운영에서는 브랜드 삭제가 막는 상태라 [deleteBrand]와 같은 일을 하지만
     * 이름에 우회를 드러낸다. 이 상태는 어긋난 데이터를 막는 테스트만 만든다.
     */
    protected fun deleteBrandKeepingProducts(brand: Brand = this.brand) {
        deleteBrand(brand)
    }

    /** 삭제 시각을 찍어 명시적으로 저장한다. 변경 감지에 기대지 않는다. */
    protected fun deleteProduct(product: Product = this.product) {
        productRepository.save(product.apply { delete() })
    }

    /**
     * 삭제 시각을 찍어 명시적으로 저장한다. 사용자·포인트 계정·주문을 삭제하는 기능은 없다. 셋은 애그리거트 루트라
     * 삭제 필터를 기본으로 가지므로(ADR 0016), 이 상태는 그 필터를 확인하는 테스트만 만든다.
     */
    protected fun deleteUser(user: User = this.user) {
        userRepository.save(user.apply { delete() })
    }

    /** [deleteUser]와 같다. */
    protected fun deletePointAccount(account: PointAccount = this.pointAccount) {
        pointAccountRepository.save(account.apply { delete() })
    }

    /** [deleteUser]와 같다. */
    protected fun deleteOrder(order: Order = this.order) {
        orderRepository.save(order.apply { delete() })
    }

    /** 좋아요 행을 지운다. 취소는 논리 삭제를 쓰지 않는다(ADR 0001). */
    protected fun unlike(like: Like = this.like) {
        likeRepository.delete(like)
    }

    /**
     * 충전한 계정을 명시적으로 저장한다. 변경 감지에 기대지 않는다. 사용자로 계정을 찾는 `findByUserId`가 저장소 테스트의
     * 대상이라 [BaseApplicationServiceTest.charge]와 달리 사용자가 아니라 계정을 받는다.
     */
    protected fun charge(amount: Money, account: PointAccount = this.pointAccount) {
        account.charge(amount)
        pointAccountRepository.save(account)
    }
}
