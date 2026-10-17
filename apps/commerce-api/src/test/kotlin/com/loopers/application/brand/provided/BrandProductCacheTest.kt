package com.loopers.application.brand.provided

import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductListRequest
import com.loopers.domain.brand.createBrandAdminRegisterRequest
import com.loopers.domain.brand.createBrandAdminUpdateRequest
import com.loopers.support.captureSql
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.loopers.support.withStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 브랜드의 쓰기가 고객 목록의 캐시를 무효로 만드는지 실제 MySQL·Redis 위에서 확인한다(ADR 0020).
 * 이름 변경과 삭제는 표시하고 등록은 표시하지 않는다. 무효화는 커밋 앞의 훅에서 일어나므로 커밋하는 기반을 쓴다.
 */
class BrandProductCacheTest(
    private val brandRegister: BrandRegister,
    private val productFinder: ProductFinder,
) : BaseCommittingApplicationServiceTest() {
    /** 항목마다 브랜드 이름을 싣는 목록이므로, 캐시해 둔 브랜드 필터 목록과 전체 목록 모두 새 이름을 보여야 한다. */
    @Test
    fun `after a brand rename commits every list read shows the new brand name on that brand's items`() {
        val other = prepareProduct(prepareBrand(name = "무신사"))
        val renamed = prepareBrand(name = "루퍼스")
        val older = prepareProduct(renamed)
        val newer = prepareProduct(renamed)
        val byBrand = ProductListRequest(brandId = renamed.id)
        val all = ProductListRequest()
        val cachedByBrand = productFinder.findAll(byBrand)
        val cachedAll = productFinder.findAll(all)

        brandRegister.update(renamed.id, createBrandAdminUpdateRequest(name = "루퍼스 스토어"))

        val rereadByBrand = productFinder.findAll(byBrand)
        val rereadAll = productFinder.findAll(all)
        assertThat(cachedByBrand.items.map { it.brandName }).containsOnly("루퍼스")
        assertThat(cachedAll.items.map { it.brandName }).containsExactly("루퍼스", "루퍼스", "무신사")
        assertThat(rereadByBrand.items.map { it.id to it.brandName })
            .containsExactly(newer.id to "루퍼스 스토어", older.id to "루퍼스 스토어")
        assertThat(rereadAll.items.map { it.id to it.brandName })
            .containsExactly(newer.id to "루퍼스 스토어", older.id to "루퍼스 스토어", other.id to "무신사")
    }

    /** 올리기는 브랜드의 UPDATE 바로 뒤, 트랜잭션의 마지막 SQL이다(ADR 0020 규칙 3). */
    @Test
    fun `the upsert is the last statement of a brand rename right after the brand UPDATE`() {
        prepareBrand()

        val statements = captureSql { brandRegister.update(brand.id, createBrandAdminUpdateRequest()) }.statements

        assertThat(statements.takeLast(2)).satisfiesExactly(
            { assertThat(it).startsWith("update brand ") },
            { assertThat(it).startsWith("insert into product_list_revision") },
        )
    }

    /** 이름 변경은 이름이 그대로여도 표시한다. 바뀌었는지 가리는 분기는 값어치가 없다. 바뀐 것이 없으면 Hibernate는 UPDATE를 보내지 않는다. */
    @Test
    fun `a rename to the same name still raises the revision at commit`() {
        prepareBrand(name = "루퍼스")

        val statements = captureSql { brandRegister.update(brand.id, createBrandAdminUpdateRequest(name = "루퍼스")) }.statements

        assertThat(statements.last()).startsWith("insert into product_list_revision")
    }

    @Test
    fun `after a brand deletion commits its products are gone from the brand list and the whole catalog`() {
        val other = prepareProduct(prepareBrand())
        val deleted = prepareBrand()
        prepareProduct(deleted)
        prepareProduct(deleted)
        val byBrand = ProductListRequest(brandId = deleted.id)
        val all = ProductListRequest()
        productFinder.findAll(byBrand)
        productFinder.findAll(all)

        brandRegister.delete(deleted.id)

        assertThat(productFinder.findAll(byBrand).items).isEmpty()
        assertThat(productFinder.findAll(all).items.map { it.id }).containsExactly(other.id)
    }

    /**
     * 올리기는 연쇄가 묶어 보낸 상품의 UPDATE와 브랜드의 UPDATE 뒤, 트랜잭션의 마지막 SQL이다.
     * 그래서 revision을 쥔 채 다른 행을 기다리지 않고, 연쇄가 UPDATE를 보내는 동안 revision을 쥐지 않는다(ADR 0020 규칙 3).
     */
    @Test
    fun `the upsert is the last statement of a brand deletion after the batched product UPDATEs and the brand UPDATE`() {
        prepareBrand()
        prepareProduct(brand)
        prepareProduct(brand)

        val statements = captureSql { brandRegister.delete(brand.id) }.statements

        val upsert = statements.indexOfFirst { it.startsWith("insert into product_list_revision") }
        assertThat(upsert).isEqualTo(statements.lastIndex)
        assertThat(statements.indexOfLast { it.startsWith("update product ") }).isBetween(0, upsert - 1)
        assertThat(statements.indexOfLast { it.startsWith("update brand ") }).isBetween(0, upsert - 1)
    }

    /** 연쇄는 지울 상품이 있는지 가리지 않고 표시한다. 그 분기는 값어치가 없다. */
    @Test
    fun `deleting a brand without products still raises the revision at commit`() {
        prepareBrand()

        val statements = captureSql { brandRegister.delete(brand.id) }.statements

        assertThat(statements.last()).startsWith("insert into product_list_revision")
    }

    /** 새 브랜드에는 상품이 없어 어느 목록도 바뀌지 않는다. 그래서 등록 뒤의 조회는 revision 하나만 읽는 hit다. */
    @Test
    fun `a brand register keeps the cached list a hit`() {
        prepareProduct()
        productFinder.findAll(ProductListRequest())

        brandRegister.register(createBrandAdminRegisterRequest())

        entityManager.withStatistics { statistics ->
            productFinder.findAll(ProductListRequest())

            assertThat(statistics.prepareStatementCount).isEqualTo(1L)
        }
    }
}
