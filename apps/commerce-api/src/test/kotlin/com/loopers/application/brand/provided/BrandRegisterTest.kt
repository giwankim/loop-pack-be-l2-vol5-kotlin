package com.loopers.application.brand.provided

import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.brand.createBrandAdminRegisterRequest
import com.loopers.domain.brand.createBrandAdminUpdateRequest
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

/**
 * [BrandRegister]를 실제 MySQL 위에서 확인한다. 테스트 트랜잭션이 포트의 트랜잭션을 감싸므로 테스트마다 롤백으로 정리한다([BaseApplicationServiceTest]).
 * 같은 트랜잭션 안에서는 영속성 컨텍스트가 조회를 가로채므로, 저장 뒤에 flush/clear를 해서 다음 조회가 SQL을 실제로 보내게 한다.
 * 결과는 같은 조각의 [BrandFinder]로 읽는다. 삭제가 함께 삭제하는 상품은 상품 조각의 [ProductFinder]로 읽고,
 * 어느 포트로도 읽히지 않는 삭제된 상품의 삭제 시각만 [JdbcTemplate]으로 읽는다.
 */
class BrandRegisterTest(
    private val brandRegister: BrandRegister,
    private val brandFinder: BrandFinder,
    private val productFinder: ProductFinder,
    private val jdbc: JdbcTemplate,
) : BaseApplicationServiceTest() {
    @Test
    fun `registering an untaken name saves a brand that can be fetched back`() {
        val request = createBrandAdminRegisterRequest()

        val registered = brandRegister.register(request)
        entityManager.flushAndClear()

        val found = brandFinder.find(registered.id)

        assertThat(registered.name).isEqualTo(request.name)
        assertThat(found).isNotSameAs(registered)
        assertThat(found.id).isEqualTo(registered.id)
        assertThat(found.name).isEqualTo(request.name)
        assertThat(found.createdAt).isNotNull()
        assertThat(found.updatedAt).isNotNull()
    }

    @Test
    fun `registering a name that matches an existing brand throws BRAND_NAME_DUPLICATED and saves nothing`() {
        val existing = prepareBrand(name = "루퍼스")
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandRegister.register(createBrandAdminRegisterRequest(name = "루퍼스")) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED)
        assertThat(countBrands()).isOne()
        assertThat(brandFinder.find(existing.id).name).isEqualTo("루퍼스")
    }

    @Test
    fun `registering a name that differs from an existing brand only in letter case throws BRAND_NAME_DUPLICATED`() {
        prepareBrand(name = "Loopers")
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandRegister.register(createBrandAdminRegisterRequest(name = "LOOPERS")) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED)
        assertThat(countBrands()).isOne()
    }

    @Test
    fun `registering a blank name is rejected by request validation before the domain and saves nothing`() {
        val exception = assertThrows<ConstraintViolationException> {
            brandRegister.register(createBrandAdminRegisterRequest(name = "   "))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("브랜드 이름은 공백일 수 없습니다.")
        assertThat(countBrands()).isZero()
    }

    @Test
    fun `updating a brand replaces its name with the one sent`() {
        prepareBrand()
        entityManager.flushAndClear()

        brandRegister.update(brand.id, createBrandAdminUpdateRequest(name = " 무신사 "))
        entityManager.flushAndClear()

        assertThat(brandFinder.find(brand.id).name).isEqualTo(" 무신사 ")
    }

    @Test
    fun `updating to a name another active brand uses throws BRAND_NAME_DUPLICATED and keeps the old name`() {
        prepareBrand(name = "루퍼스")
        val renamed = prepareBrand(name = "무신사")
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> {
            brandRegister.update(renamed.id, createBrandAdminUpdateRequest(name = "루퍼스"))
        }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NAME_DUPLICATED)
        assertThat(brandFinder.find(renamed.id).name).isEqualTo("무신사")
    }

    /** 삭제된 브랜드는 없는 브랜드이므로 그 이름은 비어 있다. 등록뿐 아니라 수정도 그 이름을 가져갈 수 있어야 한다. */
    @Test
    fun `a deleted brand frees its name for a rename`() {
        val deleted = prepareBrand(name = "루퍼스")
        val renamed = prepareBrand()
        entityManager.flushAndClear()
        deleteBrand(deleted)
        entityManager.flushAndClear()

        brandRegister.update(renamed.id, createBrandAdminUpdateRequest(name = "루퍼스"))
        entityManager.flushAndClear()

        assertThat(brandFinder.find(renamed.id).name).isEqualTo("루퍼스")
    }

    @Test
    fun `updating a brand to its own name in a different letter case is not a duplicate`() {
        prepareBrand(name = "Loopers")
        entityManager.flushAndClear()

        brandRegister.update(brand.id, createBrandAdminUpdateRequest(name = "LOOPERS"))
        entityManager.flushAndClear()

        assertThat(brandFinder.find(brand.id).name).isEqualTo("LOOPERS")
    }

    @Test
    fun `updating a blank name is rejected by request validation before the domain and keeps the old name`() {
        prepareBrand(name = "루퍼스")
        entityManager.flushAndClear()

        val exception = assertThrows<ConstraintViolationException> {
            brandRegister.update(brand.id, createBrandAdminUpdateRequest(name = "   "))
        }
        entityManager.flushAndClear()

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("브랜드 이름은 공백일 수 없습니다.")
        assertThat(brandFinder.find(brand.id).name).isEqualTo("루퍼스")
    }

    @Test
    fun `updating an unknown brand throws BRAND_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { brandRegister.update(999L, createBrandAdminUpdateRequest()) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    @Test
    fun `a deleted brand is gone from the detail and from the list`() {
        prepareBrand()
        entityManager.flushAndClear()

        brandRegister.delete(brand.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandFinder.find(brand.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        assertThat(brandFinder.findAll(BrandAdminListRequest()).content).isEmpty()
        assertThat(countBrands()).isZero()
    }

    @Test
    fun `deleting a brand twice throws BRAND_NOT_FOUND the second time`() {
        prepareBrand()
        entityManager.flushAndClear()
        deleteBrand()
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandRegister.delete(brand.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    @Test
    fun `a deleted brand frees its name for a new brand`() {
        prepareBrand()
        entityManager.flushAndClear()
        deleteBrand()
        entityManager.flushAndClear()

        val reregistered = brandRegister.register(createBrandAdminRegisterRequest(name = brand.name))

        assertThat(reregistered.id).isNotEqualTo(brand.id)
    }

    /** 재고가 비었다고 상품이 없는 것은 아니다. 연쇄는 재고를 보지 않는다(ADR 0017). */
    @Test
    fun `deleting a brand deletes every undeleted product of it, out-of-stock ones included`() {
        prepareBrand()
        val inStock = prepareProduct(brand)
        val soldOut = prepareProduct(brand, stock = 0)
        entityManager.flushAndClear()

        brandRegister.delete(brand.id)
        entityManager.flushAndClear()

        listOf(inStock, soldOut).forEach { product ->
            val exception = assertThrows<CoreException> { productFinder.find(product.id) }

            assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        }
        val exception = assertThrows<CoreException> { brandFinder.find(brand.id) }
        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    /**
     * 이미 삭제된 상품은 삭제되지 않은 상품이 아니므로 연쇄가 건드리지 않고, 처음 삭제된 시각이 그대로 남는다(ADR 0017).
     * 그 시각을 정해 둔 값으로 덮어써 두어, 연쇄가 다시 찍으면 값이 달라지게 한다.
     * 삭제가 행을 지우지 않고 시각만 찍는다는 것은 네이티브 조회를 가진 `BrandAdminApiTest`가 확인한다.
     */
    @Test
    fun `deleting a brand keeps the deletion time of a product deleted before it`() {
        prepareProduct()
        deleteProduct()
        entityManager.flush()
        jdbc.update("update product set deleted_at = '2020-01-01 00:00:00.123456' where id = ?", product.id)
        entityManager.clear()

        brandRegister.delete(brand.id)
        entityManager.flushAndClear()

        val deletedAt = jdbc.queryForObject("select deleted_at from product where id = ?", LocalDateTime::class.java, product.id)
        assertThat(deletedAt).isEqualTo(LocalDateTime.parse("2020-01-01T00:00:00.123456"))
        val exception = assertThrows<CoreException> { brandFinder.find(brand.id) }
        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    /** 연쇄는 삭제하는 브랜드의 상품만 읽으므로 다른 브랜드와 그 상품에는 UPDATE가 나가지 않는다. 수정 시각까지 그대로다. */
    @Test
    fun `deleting a brand leaves other brands and their products as they were`() {
        val deleted = prepareBrand()
        prepareProduct(deleted)
        val kept = prepareBrand()
        val keptProduct = prepareProduct(kept)
        entityManager.flushAndClear()

        brandRegister.delete(deleted.id)
        entityManager.flushAndClear()

        val foundBrand = brandFinder.find(kept.id)
        val foundProduct = productFinder.find(keptProduct.id)
        assertThat(foundBrand.name).isEqualTo(kept.name)
        assertThat(foundBrand.updatedAt).isEqualTo(kept.updatedAt)
        assertThat(foundProduct.name).isEqualTo(keptProduct.name)
        assertThat(foundProduct.stock).isEqualTo(keptProduct.stock)
        assertThat(foundProduct.updatedAt).isEqualTo(keptProduct.updatedAt)
    }

    /** 연쇄할 상품이 없어도 같은 흐름으로 삭제된다. */
    @Test
    fun `deleting a brand that never had a product leaves it gone from the detail`() {
        prepareBrand()
        entityManager.flushAndClear()

        brandRegister.delete(brand.id)
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { brandFinder.find(brand.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
    }

    /** 삭제되지 않은 브랜드 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countBrands(): Long {
        return entityManager
            .createQuery("select count(b) from Brand b", Long::class.java)
            .singleResult
    }
}
