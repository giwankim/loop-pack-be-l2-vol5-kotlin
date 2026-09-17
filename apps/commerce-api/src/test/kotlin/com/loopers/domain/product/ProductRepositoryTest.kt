package com.loopers.domain.product

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandRepositoryImpl
import com.loopers.infrastructure.product.ProductRepositoryImpl
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
 * [ProductRepository] 계약을 실제 MySQL에서 확인한다. 구현이 무엇인지는 보지 않고 인터페이스로만 부른다.
 * 설정과 정리 방식은 [com.loopers.domain.brand.BrandRepositoryTest]와 같다.
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

    private fun product(brand: Brand, price: Long = 10_000, stock: Int = 1) =
        Product(brand = brand, name = "티셔츠", price = Money(price), stock = Stock(stock))
}
