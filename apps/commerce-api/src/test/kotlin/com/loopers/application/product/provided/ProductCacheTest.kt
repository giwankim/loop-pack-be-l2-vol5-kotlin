package com.loopers.application.product.provided

import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.product.ProductListCacheKeyGenerator
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
                ProductSummaryInfo(
                    id = shirt.id,
                    name = "티셔츠",
                    price = 12_000L,
                    soldOut = true,
                    brandId = brand.id,
                    brandName = "루퍼스",
                    likeCount = 0L,
                ),
                ProductSummaryInfo(
                    id = socks.id,
                    name = "양말",
                    price = 3_000L,
                    soldOut = false,
                    brandId = brand.id,
                    brandName = "루퍼스",
                    likeCount = 1L,
                ),
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
     * 검증을 지난 요청은 결과가 비어도 캐시한다. 모르는 브랜드, 삭제된 브랜드, 끝을 지난 쪽은 빈 목록이고 두 번째 읽기는
     * revision 하나만 읽는 hit다. 마지막 쪽(`hasNext`가 거짓)이 캐시되는 것은 위 테스트가 본다.
     * 브랜드의 삭제도 revision을 올리므로 캐시는 준비를 마친 뒤에 채운다.
     */
    @Test
    fun `an unknown brand, a deleted brand and a page past the end are cached as empty lists`() {
        prepareProduct()
        val deletedBrand = prepareBrand()
        deleteBrand(deletedBrand)
        val requests = listOf(
            ProductListRequest(brandId = 999L),
            ProductListRequest(brandId = deletedBrand.id),
            ProductListRequest(page = 1),
        )

        requests.forEach { request ->
            val miss = productFinder.findAll(request)

            assertThat(miss.items).isEmpty()
            assertThat(miss.hasNext).isFalse()
            assertNextReadIsHit(request)
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
     * 전체 목록과 그 브랜드의 목록을 채운 뒤 상품을 등록하면, 다시 읽은 두 목록 모두 새 상품을 맨 앞에 보인다.
     * 다른 브랜드의 상품을 하나 두어 두 목록이 서로 다른 키와 다른 결과가 되게 한다.
     */
    @Test
    fun `after a product register commits the whole catalog and its brand's list show the new product`() {
        val ownBrand = prepareBrand()
        val socks = prepareProduct(ownBrand)
        val otherBrandProduct = prepareProduct(prepareBrand())
        val wholeCatalog = ProductListRequest()
        val brandList = ProductListRequest(brandId = ownBrand.id)
        productFinder.findAll(wholeCatalog)
        productFinder.findAll(brandList)

        val shirt = prepareProduct(ownBrand)

        assertThat(productFinder.findAll(wholeCatalog).items.map { it.id })
            .containsExactly(shirt.id, otherBrandProduct.id, socks.id)
        assertThat(productFinder.findAll(brandList).items.map { it.id }).containsExactly(shirt.id, socks.id)
    }

    @Test
    fun `after a product delete commits the cached list no longer shows the product`() {
        val socks = prepareProduct()
        val shirt = prepareProduct(brand)
        val request = ProductListRequest()
        productFinder.findAll(request)

        deleteProduct(shirt)

        assertThat(productFinder.findAll(request).items.map { it.id }).containsExactly(socks.id)
    }

    /** 고객은 재고를 품절 여부로만 본다. 관리자가 재고를 0으로 맞추면 다시 읽은 목록에 품절로 보인다. */
    @Test
    fun `after an admin stock update to zero commits the list shows the product sold out`() {
        prepareProduct(stock = 5)
        val request = ProductListRequest()
        productFinder.findAll(request)

        updateProductStock(quantity = 0)

        assertThat(productFinder.findAll(request).items.map { it.soldOut }).containsExactly(true)
    }

    @Test
    fun `after an admin restocks a sold out product the list shows it available again`() {
        prepareProduct(stock = 0)
        val request = ProductListRequest()
        productFinder.findAll(request)

        updateProductStock(quantity = 5)

        assertThat(productFinder.findAll(request).items.map { it.soldOut }).containsExactly(false)
    }

    /** 품절이 그대로인 재고 수정은 목록을 바꾸지 않는다. revision을 올리지 않으므로 다음 읽기는 revision 하나만 읽는 hit다. */
    @Test
    fun `an admin stock update that leaves sold out unchanged sends no upsert and the next read is a hit`() {
        prepareProduct(stock = 5)
        val request = ProductListRequest()
        productFinder.findAll(request)

        val captured = captureSql { updateProductStock(quantity = 3) }

        assertThat(captured.statements).noneMatch { it.startsWith("insert into product_list_revision") }
        assertNextReadIsHit(request)
    }

    /** 마지막 하나를 판 주문 확정은 상품을 품절로 바꾼다. 표시는 주문 조각이 아니라 재고를 줄이는 상품 조각이 한다. */
    @Test
    fun `after an order confirmation sells the last unit the list shows the product sold out`() {
        prepareConfirmableOrder(stock = 1, quantity = 1)
        val request = ProductListRequest()
        productFinder.findAll(request)

        confirmOrder()

        assertThat(productFinder.findAll(request).items.map { it.soldOut }).containsExactly(true)
    }

    @Test
    fun `an order confirmation that leaves sold out unchanged sends no upsert and the next read is a hit`() {
        prepareConfirmableOrder(stock = 5, quantity = 2)
        val request = ProductListRequest()
        productFinder.findAll(request)

        val captured = captureSql { confirmOrder() }

        assertThat(captured.statements).noneMatch { it.startsWith("insert into product_list_revision") }
        assertNextReadIsHit(request)
    }

    /**
     * 품절을 만든 확정에서 올리기는 마지막 SQL이다. 주문, 상품, 포인트 계정의 UPDATE가 모두 그 앞에 나가므로,
     * 잠금의 차례는 주문 → 상품 → 포인트 계정 → revision이다(ADR 0020 규칙 3).
     */
    @Test
    fun `the upsert of a confirmation that sells out is its last statement after the order, product and point account updates`() {
        prepareConfirmableOrder(stock = 1, quantity = 1)

        val captured = captureSql { confirmOrder() }

        val upsert = captured.statements.last()
        val updates = captured.statements.dropLast(1)
        assertThat(upsert).startsWith("insert into product_list_revision")
        assertThat(updates).anyMatch { it.startsWith("update orders ") }
        assertThat(updates).anyMatch { it.startsWith("update product ") }
        assertThat(updates).anyMatch { it.startsWith("update point_account ") }
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
        assertThat(currentListRevision()).isOne()
    }

    /** 재고가 [stock]개인 상품 하나를 [quantity]개 담은 주문을 만들고, 확정할 수 있게 그 합계만큼 충전한다. */
    private fun prepareConfirmableOrder(stock: Int, quantity: Int) {
        prepareOrder(products = listOf(prepareProduct(stock = stock)), quantity = quantity)
        charge(amount = order.totalAmount.amount.longValueExact())
    }

    /** 다음 읽기가 revision 하나만 읽는 hit인지 본다. */
    private fun assertNextReadIsHit(request: ProductListRequest) {
        entityManager.withStatistics { statistics ->
            productFinder.findAll(request)

            assertThat(statistics.prepareStatementCount).isEqualTo(1L)
        }
    }
}
