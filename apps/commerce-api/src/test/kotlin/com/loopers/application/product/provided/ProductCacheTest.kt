package com.loopers.application.product.provided

import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.product.ProductListCacheKeyGenerator
import com.loopers.application.product.required.ProductListRevisionRepository
import com.loopers.domain.shared.Money
import com.loopers.support.captureSql
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.loopers.support.withStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 고객 목록의 캐시와 그것을 무효로 만드는 상품 목록 revision을 실제 MySQL·Redis 위에서 확인한다(ADR 0020).
 *
 * 롤백하는 테스트에서는 `beforeCommit`이 돌지 않아 쓰기가 revision을 올리지 않는다. 무효화를 보려면 쓰기가 커밋해야 하므로
 * 커밋하는 기반을 쓴다. 기반이 테스트마다 표와 Redis를 함께 비우므로 테스트는 revision 0과 빈 캐시에서 시작한다.
 */
class ProductCacheTest(
    private val productFinder: ProductFinder,
    private val orderFinder: OrderFinder,
    private val productListChangeMarker: ProductListChangeMarker,
    private val productListRevisionRepository: ProductListRevisionRepository,
    private val productListCacheKeyGenerator: ProductListCacheKeyGenerator,
) : BaseCommittingApplicationServiceTest() {
    /**
     * miss는 revision, 페이지, 좋아요 수의 세 문장이고 hit는 revision 하나다. 두 결과는 같고, 준비한 행에서 손으로 적은 페이지와 같다.
     * 기본 정렬은 늦게 등록된 상품이 앞선다.
     */
    @Test
    fun `the same list request twice reads the database once and the cache the second time with the same page`() {
        prepareBrand(name = "루퍼스")
        val socks = prepareProduct(brand, name = "양말", price = 3_000, stock = 5)
        val shirt = prepareProduct(brand, name = "티셔츠", price = 12_000, stock = 0)
        prepareLike(product = socks)
        val expected = ProductListInfo(
            items = listOf(
                ProductSummaryInfo(shirt.id, "티셔츠", 12_000L, soldOut = true, brand.id, "루퍼스", likeCount = 0L),
                ProductSummaryInfo(socks.id, "양말", 3_000L, soldOut = false, brand.id, "루퍼스", likeCount = 1L),
            ),
            page = 0,
            size = ProductListRequest.DEFAULT_SIZE,
            hasNext = false,
        )

        entityManager.withStatistics { statistics ->
            val miss = productFinder.findAll(ProductListRequest())

            assertThat(miss).isEqualTo(expected)
            assertThat(statistics.prepareStatementCount).isEqualTo(3L)
        }
        entityManager.withStatistics { statistics ->
            val hit = captureSql { productFinder.findAll(ProductListRequest()) }

            assertThat(hit.result).isEqualTo(expected)
            assertThat(statistics.prepareStatementCount).isEqualTo(1L)
            assertThat(hit.statements).singleElement().asString().contains("from product_list_revision")
        }
    }

    /**
     * 과제 4절의 가격 수정 장면. 캐시를 채운 뒤 관리자가 가격을 내리고 커밋하면, 다시 읽은 목록에 새 가격과 새 `price_asc` 차례가 보인다.
     * 수정 전에 만든 주문은 그때의 단가를 스냅숏했으므로 금액이 그대로다.
     */
    @Test
    fun `after an admin price update commits the list shows the new price and order and an earlier order keeps its amount`() {
        prepareBrand()
        val socks = prepareProduct(brand, price = 5_000)
        val shirt = prepareProduct(brand, price = 8_000)
        prepareOrder(products = listOf(shirt), quantity = 2)
        val request = ProductListRequest(sort = "price_asc")
        val cached = productFinder.findAll(request)

        updateProduct(price = 1_000, product = shirt)

        val reread = productFinder.findAll(request)
        assertThat(cached.items.map { it.id to it.price }).containsExactly(socks.id to 5_000L, shirt.id to 8_000L)
        assertThat(reread.items.map { it.id to it.price }).containsExactly(shirt.id to 1_000L, socks.id to 5_000L)
        assertThat(inNewTransaction { orderFinder.find(user.id, order.id).totalAmount }).isEqualTo(Money(16_000))
    }

    /**
     * 올리기는 트랜잭션의 마지막 SQL이고 상품의 UPDATE 바로 뒤다. 그래서 revision을 쥔 트랜잭션은 다른 잠금을 기다리지 않는다(ADR 0020 규칙 3).
     * UPDATE는 커밋의 flush가 아니라 커밋 앞의 훅이 먼저 flush해 보낸다. 그렇지 않으면 올리기가 UPDATE보다 앞선다.
     */
    @Test
    fun `the upsert is the last statement of a product update transaction right after its UPDATE`() {
        prepareProduct()

        val captured = captureSql { updateProduct(price = 1_000) }

        assertThat(captured.statements.takeLast(2)).satisfiesExactly(
            { assertThat(it).startsWith("update product ") },
            { assertThat(it).startsWith("insert into product_list_revision") },
        )
    }

    /**
     * 키의 revision은 목록의 읽기 전용 트랜잭션에서 읽어야 페이지와 같은 스냅샷이다. 트랜잭션 밖에서는 키를 만들지 않는다.
     * 목록이 위에서 캐시되는 것은 트랜잭션 advisor가 캐시 advisor를 감싸 키 생성기가 그 트랜잭션 안에서 불리기 때문이다.
     */
    @Test
    fun `the list cache key generator throws without an active transaction`() {
        val findAll = ProductFinder::class.java.getMethod("findAll", ProductListRequest::class.java)

        assertThrows<IllegalStateException> {
            productListCacheKeyGenerator.generate(productFinder, findAll, ProductListRequest())
        }
    }

    /** 트랜잭션 밖의 표시는 올릴 커밋이 없어 조용히 사라진다. 그래서 곧바로 실패한다. */
    @Test
    fun `marking the list changed outside a transaction throws`() {
        assertThrows<IllegalStateException> { productListChangeMarker.markChanged() }
    }

    /** 표시가 몇 번이든 커밋 앞에서 한 번 올린다. 행이 없던 revision 0이 1이 된다. */
    @Test
    fun `two marks in one transaction send one upsert at commit`() {
        val captured = captureSql {
            inNewTransaction {
                productListChangeMarker.markChanged()
                productListChangeMarker.markChanged()
            }
        }

        assertThat(captured.statements).singleElement().asString().startsWith("insert into product_list_revision")
        assertThat(inNewTransaction { productListRevisionRepository.findRevision() }).isOne()
    }
}
