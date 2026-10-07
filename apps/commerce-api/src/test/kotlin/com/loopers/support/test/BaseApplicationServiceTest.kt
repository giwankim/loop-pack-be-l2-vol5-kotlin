package com.loopers.support.test

import com.loopers.application.brand.provided.BrandRegister
import com.loopers.application.product.provided.ProductRegister
import com.loopers.application.product.required.ProductRepository
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.createBrandAdminRegisterRequest
import com.loopers.domain.product.Product
import com.loopers.domain.product.createProductAdminRegisterRequest
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
 * 포트가 `Info`를 돌려주면 엔티티를 ID로 다시 읽는다. 마지막으로 준비한 엔티티는 타입마다 필드에 남고, 다른 `prepare`의
 * 기본값으로 불린 `prepare`도 필드를 바꾼다. 테스트가 필드를 읽어도 되는 경우는 `CODING_STANDARDS.md`의 Fixture 절에 있다.
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
    private lateinit var productRegister: ProductRegister

    @Autowired
    private lateinit var productRepository: ProductRepository

    /** 마지막으로 준비한 브랜드. */
    protected lateinit var brand: Brand

    /** 마지막으로 준비한 상품. */
    protected lateinit var product: Product

    protected fun prepareBrand(name: String? = null): Brand =
        brandRegister.register(createBrandAdminRegisterRequest(name = name)).also { brand = it }

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

    protected fun deleteBrand(brand: Brand = this.brand) {
        brandRegister.delete(brand.id)
    }

    protected fun deleteProduct(product: Product = this.product) {
        productRegister.delete(product.id)
    }
}
