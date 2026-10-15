package com.loopers.adapter.webapi.v1.product

import com.loopers.application.brand.provided.BrandRegister
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.ProductQuerySeed
import com.loopers.support.SeedProduct
import com.loopers.support.captureSql
import com.loopers.support.test.BaseWebApiAdapterTest
import com.loopers.support.withStatistics
import io.github.oshai.kotlinlogging.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue

private val log = KotlinLogging.logger {}

/**
 * 과제 2절의 측정. [ProductQuerySeed]의 카탈로그 위에서 고객 상품 목록을 실제 HTTP 요청으로 보내고, 응답·요청당 SQL 수·
 * 실행 계획·지연을 잰다. 브랜드 필터 + `price_asc`는 `(brand_id, price, id DESC)` 인덱스를 숨긴 "전"과 보이는 "후"를 한 실행에서
 * 견주고, `likes_desc`는 기준선만 잰다(카탈로그 설계 5.38, 8).
 *
 * seed를 넣은 뒤의 `ANALYZE TABLE`과 인덱스를 숨겼다 보이게 하는 DDL이 열린 트랜잭션을 암묵으로 커밋하므로 롤백으로 정리할 수 없다.
 * 그래서 클래스는 테스트 트랜잭션에서 빠지고, seed는 클래스마다 한 번 넣어 끝나면 [DatabaseCleanUp]으로 표를 비운다.
 * 수만 행을 테스트마다 다시 넣지 않으려고 PER_CLASS다.
 *
 * 기대값은 SQL이 아니라 seed의 행에서 Kotlin으로 센다. 측정값은 출력만 하고 단언하지 않는다. 시간의 흔들림이 빌드를 깨지 않게 하려는 것이다.
 *
 * 같은 seed 위에서 브랜드 삭제의 연쇄가 상품을 잠그는 계획도 지킨다. 옵티마이저는 작은 표에서 다른 계획을 고를 수 있어 큰 seed가 필요하다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductQueryPerformanceApiTest(
    private val jdbc: JdbcTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
    private val brandRegister: BrandRegister,
    transactionManager: PlatformTransactionManager,
) : BaseWebApiAdapterTest() {
    companion object {
        private const val ENDPOINT = "/api/v1/products"

        /** 과제 예시의 브랜드 1…100 가운데 하나. 삭제되지 않은 상품이 97개라 첫 페이지 뒤에도 상품이 남는다. */
        private const val BRAND_ID = 7L

        /** 큰 브랜드. 가장 싼 1,000원에 삭제되지 않은 상품이 97개라 첫 페이지 전체가 한 가격이고, 차례는 동점 규칙만 정한다. */
        private const val LARGE_BRAND_ID = 101L

        private const val LARGE_BRAND_LOWEST_PRICE = 1_000

        private const val PRICE_ASC = "price_asc"

        private const val LIKES_DESC = "likes_desc"

        private const val PAGE_SIZE = 20

        /** 전후를 견주려고 숨겼다 보이게 하는 인덱스. DDL에만 쓰고 단언에는 쓰지 않는다. */
        private const val BRAND_PRICE_INDEX = "IDX_PRODUCT_ON_BRAND_ID_AND_PRICE_AND_ID"
    }

    /** 브랜드 삭제를 감싸는 바깥 트랜잭션. 서비스의 트랜잭션이 여기에 합류하고, 테스트가 롤백만 하도록 표시한다. */
    private val transaction = TransactionTemplate(transactionManager)

    private val likeCounts: Map<Long, Int> by lazy { ProductQuerySeed.likes.groupingBy { it.productId }.eachCount() }

    private val deletedBrandIds: Set<Long> by lazy {
        ProductQuerySeed.brands.filter { it.deletedAt != null }.map { it.id }.toSet()
    }

    @BeforeAll
    fun loadSeed() {
        ProductQuerySeed.load(jdbc)
    }

    @AfterAll
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `a brand's price_asc first page lists the seed's active products of that brand cheapest first`() {
        val expected = expectedPageBody(priceAscCandidates(BRAND_ID), page = 0)

        assertThat(requestGetProducts(BRAND_ID, PRICE_ASC)).hasStatusOk().bodyJson().isStrictlyEqualTo(expected)
    }

    /** 첫 페이지가 모두 한 가격이라 차례를 정하는 것은 동점 규칙뿐이다. 같은 가격이면 늦게 등록된(id가 큰) 상품이 앞선다. */
    @Test
    fun `the large brand's price_asc first page is one price tie ordered by id descending`() {
        val expected = expectedPageBody(priceAscCandidates(LARGE_BRAND_ID), page = 0)

        val body = assertThat(requestGetProducts(LARGE_BRAND_ID, PRICE_ASC)).hasStatusOk().bodyJson()
        body.isStrictlyEqualTo(expected)
        body.extractingPath("$.data.items[*].price").asArray().hasSize(PAGE_SIZE).containsOnly(LARGE_BRAND_LOWEST_PRICE)
        body.extractingPath("$.data.items[*].id").asArray().isSortedAccordingTo(compareByDescending { (it as Number).toLong() })
    }

    /**
     * 두 페이지의 기대값을 그 브랜드의 후보 전체를 센 한 차례에서 잘라 낸 0쪽과 1쪽으로 둔다. 두 응답이 그것과 엄격하게 같으면
     * 두 응답을 이은 것이 그 차례의 앞 40개이므로 경계에서 빠지거나 겹친 상품이 없다. 페이지를 자른 뒤에 정렬했다면 1쪽이 0쪽과
     * 이어지지 않는다(설계 5.32). seed에서 0쪽의 끝과 1쪽의 처음은 좋아요 13개로 동점이라 동점 규칙도 경계를 건넌다.
     */
    @Test
    fun `a brand's likes_desc pages 0 and 1 continue one order over all of the brand's active products`() {
        val candidates = likesDescCandidates(BRAND_ID)

        val body = assertThat(requestGetProducts(BRAND_ID, LIKES_DESC, page = 0)).hasStatusOk().bodyJson()
        body.isStrictlyEqualTo(expectedPageBody(candidates, page = 0))
        val list = assertThat(requestGetProducts(BRAND_ID, LIKES_DESC, page = 1)).hasStatusOk().bodyJson()
        list.isStrictlyEqualTo(expectedPageBody(candidates, page = 1))
    }

    /**
     * 삭제된 상품과 삭제된 브랜드의 상품에도 좋아요가 남아 있다. 거르지 않았다면 첫 페이지에 들었을 그런 상품이 seed에 있음을
     * 먼저 확인한다. 없으면 아래의 "빠진다"는 아무것도 걸러 보지 않고 지나간다. seed에서는 삭제된 브랜드 99의 상품 3298(좋아요 900개)이다.
     * 삭제된 브랜드의 상품은 모두 삭제됐으므로(브랜드 삭제의 불변식) 그 상품은 삭제된 상품이기도 하다. 그래서 이 페이지는 상품의 삭제
     * 조건과 브랜드의 삭제 조건 중 하나만 있어도 통과한다. 둘을 따로 보는 것은 `ProductRepositoryTest`다.
     */
    @Test
    fun `the whole catalog's likes_desc first page leaves out deleted products and the products of deleted brands`() {
        val unfiltered = ProductQuerySeed.products.sortedWith(likesDescOrder()).take(PAGE_SIZE)
        assertThat(unfiltered).anyMatch { it.deletedAt != null && it.brandId in deletedBrandIds }
        val deletedProductIds = ProductQuerySeed.products.filter { it.deletedAt != null }.map { it.id.toInt() }

        val body = assertThat(requestGetProducts(brandId = null, sort = LIKES_DESC)).hasStatusOk().bodyJson()
        body.isStrictlyEqualTo(expectedPageBody(likesDescCandidates(brandId = null), page = 0))
        body.extractingPath("$.data.items[*].id").asArray().doesNotContainAnyElementsOf(deletedProductIds)
        body.extractingPath("$.data.items[*].brand.id").asArray().doesNotContainAnyElementsOf(deletedBrandIds.map { it.toInt() })
    }

    /**
     * 페이지 쿼리 하나와 좋아요 수 배치 조회 하나다. 총 개수를 세거나 항목마다 좋아요를 세기 시작하면 늘어난다(설계 5.5, 5.28).
     * 브랜드를 비운 줄은 전체 카탈로그다.
     */
    @ParameterizedTest
    @CsvSource(
        "$BRAND_ID, $PRICE_ASC, 0",
        "$LARGE_BRAND_ID, $PRICE_ASC, 0",
        "$BRAND_ID, $LIKES_DESC, 0",
        "$BRAND_ID, $LIKES_DESC, 1",
        ", $LIKES_DESC, 0",
    )
    fun `each measured page request sends two SQL statements`(brandId: Long?, sort: String, page: Int) {
        entityManager.withStatistics { statistics ->
            assertThat(requestGetProducts(brandId, sort, page)).hasStatusOk()

            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        }
    }

    /**
     * 인덱스는 결과를 바꾸지 않고 빠르게만 해야 한다. 새 인덱스를 숨긴 "전"과 보이는 "후"의 응답이 모두 seed에서 센 기대값과
     * 엄격하게 같고, 두 본문이 글자 그대로 같다. 큰 브랜드의 첫 페이지는 한 가격이라 인덱스를 거쳐도 동점 규칙이 지켜지는지 본다.
     */
    @ParameterizedTest
    @ValueSource(longs = [BRAND_ID, LARGE_BRAND_ID])
    fun `a brand's price_asc first page is the same with the brand and price index invisible and visible`(brandId: Long) {
        val expected = expectedPageBody(priceAscCandidates(brandId), page = 0)

        val before = withBrandPriceIndexInvisible { requestGetProducts(brandId, PRICE_ASC) }
        val after = requestGetProducts(brandId, PRICE_ASC)

        assertThat(before).hasStatusOk().bodyJson().isStrictlyEqualTo(expected)
        assertThat(after).hasStatusOk().bodyJson().isStrictlyEqualTo(expected)
        assertThat(after.response.getContentAsString(Charsets.UTF_8))
            .isEqualTo(before.response.getContentAsString(Charsets.UTF_8))
    }

    /**
     * 새 인덱스가 하는 일은 정렬 단계를 없애는 것이다. 숨기면 브랜드의 상품을 모두 읽고 정렬하고, 보이면 인덱스의 차례로 읽어
     * 앞의 21행에서 멈춘다. 계획의 모양만 보고 인덱스 이름은 단언하지 않는다.
     */
    @ParameterizedTest
    @ValueSource(longs = [BRAND_ID, LARGE_BRAND_ID])
    fun `a brand's price_asc page query sorts with the brand and price index invisible and not with it visible`(brandId: Long) {
        val before = withBrandPriceIndexInvisible { pageQueryPlan(brandId, PRICE_ASC, page = 0) }
        val after = pageQueryPlan(brandId, PRICE_ASC, page = 0)

        assertThat(before).describedAs("index invisible").contains("-> Sort")
        assertThat(after).describedAs("index visible").doesNotContain("-> Sort")
    }

    @ParameterizedTest
    @ValueSource(longs = [BRAND_ID, LARGE_BRAND_ID])
    fun `the brand price_asc before and after print the plans and latency with the brand and price index invisible and visible`(
        brandId: Long,
    ) {
        withBrandPriceIndexInvisible { printMeasurement("before", brandId, PRICE_ASC, page = 0, warmUps = 50, samples = 200) }
        printMeasurement("after", brandId, PRICE_ASC, page = 0, warmUps = 50, samples = 200)
    }

    /** 집계로 정렬하므로 1쪽도 후보 전체를 센다. 두 쪽의 계획을 나란히 출력해 비용이 같은지 본다(설계 5.32). */
    @Test
    fun `the brand likes_desc baseline prints the plans and latency of pages 0 and 1`() {
        printMeasurement("baseline", BRAND_ID, LIKES_DESC, page = 0, warmUps = 50, samples = 200)
        printMeasurement("baseline", BRAND_ID, LIKES_DESC, page = 1, warmUps = 50, samples = 200)
    }

    /** 요청 하나가 80 ms 남짓 걸리므로 준비 10회, 측정 50회만 보낸다. */
    @Test
    fun `the whole catalog likes_desc baseline prints the plans and latency of the first page`() {
        printMeasurement("baseline", brandId = null, sort = LIKES_DESC, page = 0, warmUps = 10, samples = 50)
    }

    private fun requestGetProducts(brandId: Long?, sort: String, page: Int = 0): MvcTestResult {
        return mvc.get().uri(ENDPOINT)
            .apply { brandId?.let { queryParam("brandId", it.toString()) } }
            .queryParam("sort", sort)
            .queryParam("page", page.toString())
            .exchange()
    }

    /**
     * [state]("before", "after", "baseline")의 측정을 출력한다. 요청이 실제로 보낸 두 문장을 잡아 같은 바인드 값으로 EXPLAIN하고,
     * 준비 요청 뒤에 지연을 잰다. 문장이 둘이라는 단언은 바인드 값을 문장에 맞추려는 것이고, 시간이나 계획의 모양은 단언하지 않는다.
     * 좋아요 수 배치 조회의 바인드 값은 그 페이지의 상품 id다. 응답이 그 페이지와 같다는 것은 기대값 테스트가 지킨다.
     */
    private fun printMeasurement(state: String, brandId: Long?, sort: String, page: Int, warmUps: Int, samples: Int) {
        val captured = captureSql { requestGetProducts(brandId, sort, page) }
        assertThat(captured.result).hasStatusOk()
        assertThat(captured.statements).hasSize(2)
        val (pageQuery, likeCountQuery) = captured.statements
        val pageIds = sortedCandidates(brandId, sort).drop(page * PAGE_SIZE).take(PAGE_SIZE).map { it.id }

        val latency = measureLatency(warmUps, samples) { requestGetProducts(brandId, sort, page) }

        log.info {
            """
            |$state: brand ${brandId ?: "(whole catalog)"} $sort page $page size $PAGE_SIZE
            |${plans("page query", pageQuery, pageBinds(brandId, page))}
            |${plans("like count query", likeCountQuery, pageIds)}
            |latency $latency
            """.trimMargin()
        }
    }

    /** 요청이 실제로 보낸 페이지 쿼리의 `EXPLAIN FORMAT=TREE`. 요청의 첫 문장이 페이지 쿼리다. */
    private fun pageQueryPlan(brandId: Long?, sort: String, page: Int): String {
        val captured = captureSql { requestGetProducts(brandId, sort, page) }
        assertThat(captured.result).hasStatusOk()

        return explainTree(captured.statements.first(), pageBinds(brandId, page))
    }

    /** 페이지 쿼리의 바인드 값. 브랜드(있으면), offset, `size + 1`이다. */
    private fun pageBinds(brandId: Long?, page: Int): List<Any> {
        return listOfNotNull<Any>(brandId) + listOf(page * PAGE_SIZE, PAGE_SIZE + 1)
    }

    /**
     * [block]을 새 인덱스를 숨긴 채 돌린다. 옵티마이저는 숨은 인덱스를 보지 않고, 쓰기는 계속 그 인덱스를 고친다.
     * 블록이 실패해도 인덱스를 다시 보이게 한다. 스키마는 같은 Spring 컨텍스트를 쓰는 다음 클래스에도 남기 때문이다.
     * DDL이라 MySQL이 열린 트랜잭션을 암묵으로 커밋한다. 이 클래스가 테스트 트랜잭션에서 빠지는 까닭 중 하나다.
     */
    private fun <T> withBrandPriceIndexInvisible(block: () -> T): T {
        jdbc.execute("alter table product alter index $BRAND_PRICE_INDEX invisible")
        try {
            return block()
        } finally {
            jdbc.execute("alter table product alter index $BRAND_PRICE_INDEX visible")
        }
    }

    /**
     * 브랜드 삭제의 연쇄가 상품을 id 오름차순으로 잠그는지 지킨다(ADR 0018 규칙 6의 2026-10-15 덧붙임, 규칙 3).
     *
     * 잠금은 `ORDER BY`가 아니라 훑는 인덱스의 차례로 걸린다. 연쇄가 어느 인덱스를 쓸지는 옵티마이저가 고르고,
     * `(brand_id, deleted_at, price, id DESC)`를 고른 계획은 가장 싼 상품부터 잠그고 `Sort: p1_0.id`로 돌려줄 행만 다시 늘어놓았다.
     * 그래서 계획에 정렬 단계가 없음을 단언한다. 정렬 없이 `ORDER BY id`에 답하는 훑기는 id 차례로 읽고 그 차례로 잠근다.
     * 인덱스 이름은 단언하지 않는다.
     *
     * 실제 연쇄를 [BrandRegister.delete]로 돌려 그 잠금 읽기를 잡는다. 상품의 UPDATE는 커밋의 flush가 보내므로 블록 안에서 flush해
     * 연쇄 전체를 보낸 뒤, 바깥 트랜잭션을 롤백해 브랜드와 상품을 삭제되지 않은 채로 둔다. 그래서 이 클래스의 다른 테스트는 차례와
     * 상관없이 같은 seed를 본다. EXPLAIN은 문장을 실행하지 않으므로 잠그지 않는다.
     */
    @Test
    fun `the brand delete cascade locks the brand's products without a sort step in its plan`() {
        val captured = transaction.execute { status ->
            status.setRollbackOnly()
            captureSql {
                brandRegister.delete(BRAND_ID)
                entityManager.flush()
            }
        }!!
        val productLockRead = captured.statements.single { it.contains("from product") && it.contains(" for update") }

        val plan = explainTree(productLockRead, listOf(BRAND_ID))

        assertThat(plan).describedAs("EXPLAIN FORMAT=TREE of %s", productLockRead).doesNotContain("-> Sort")
        assertThat(activeBrandCount(BRAND_ID)).isEqualTo(1)
        assertThat(activeProductCount(BRAND_ID))
            .isEqualTo(ProductQuerySeed.products.count { it.brandId == BRAND_ID && it.deletedAt == null })
    }

    private fun sortedCandidates(brandId: Long?, sort: String): List<SeedProduct> {
        return when (sort) {
            PRICE_ASC -> priceAscCandidates(checkNotNull(brandId) { "가격 낮은순은 브랜드를 골라야 잰다" })
            LIKES_DESC -> likesDescCandidates(brandId)
            else -> error("재지 않는 정렬: $sort")
        }
    }

    /**
     * seed에서 센 `price_asc`의 후보 전체. 삭제된 상품을 빼고 가격 오름차순, 같은 가격이면 늦게 등록된(id가 큰) 상품이 앞선다.
     * 삭제된 브랜드는 고르지 않으므로 브랜드의 삭제는 보지 않는다.
     */
    private fun priceAscCandidates(brandId: Long): List<SeedProduct> {
        return ProductQuerySeed.products
            .filter { it.brandId == brandId && it.deletedAt == null }
            .sortedWith(compareBy<SeedProduct> { it.price }.thenByDescending { it.id })
    }

    /**
     * seed에서 센 `likes_desc`의 후보 전체. [brandId]가 null이면 전체 카탈로그다. 삭제된 상품과 삭제된 브랜드의 상품을 빼고,
     * 후보 전체를 좋아요 수 내림차순, 같은 수면 id 내림차순으로 세운다. 좋아요가 없는 상품은 0으로 끝에 온다.
     */
    private fun likesDescCandidates(brandId: Long?): List<SeedProduct> {
        return ProductQuerySeed.products
            .filter { (brandId == null || it.brandId == brandId) && it.deletedAt == null && it.brandId !in deletedBrandIds }
            .sortedWith(likesDescOrder())
    }

    private fun likesDescOrder(): Comparator<SeedProduct> {
        return compareByDescending<SeedProduct> { likeCounts[it.id] ?: 0 }.thenByDescending { it.id }
    }

    /**
     * [candidates]의 [page]쪽을 응답 본문 그대로 손으로 적는다. 좋아요 수는 seed의 좋아요를 상품마다 센다.
     * 엄격하게 견주므로 값뿐 아니라 빠지거나 더 실린 필드, JSON 타입, 항목의 차례가 모두 걸린다.
     */
    private fun expectedPageBody(candidates: List<SeedProduct>, page: Int): String {
        val brandNames = ProductQuerySeed.brands.associate { it.id to it.name }
        val items = candidates.drop(page * PAGE_SIZE).take(PAGE_SIZE).joinToString(",") { product ->
            """{"id":${product.id},"name":"${product.name}","price":${product.price},"soldOut":${product.stock == 0},""" +
                """"brand":{"id":${product.brandId},"name":"${brandNames.getValue(product.brandId)}"},""" +
                """"likeCount":${likeCounts[product.id] ?: 0}}"""
        }
        val hasNext = candidates.size > (page + 1) * PAGE_SIZE

        return """{"meta":{"result":"SUCCESS"},"data":{"items":[$items],"page":$page,"size":$PAGE_SIZE,"hasNext":$hasNext}}"""
    }

    /**
     * [sql]의 `EXPLAIN FORMAT=TREE`와 `EXPLAIN ANALYZE`. EXPLAIN은 JDBC로 보내므로 [captureSql]에 잡히지 않는다.
     * `EXPLAIN ANALYZE`는 문장을 실제로 실행해 행 수와 시간을 잰다.
     */
    private fun plans(label: String, sql: String, binds: List<Any>): String {
        val tree = explainTree(sql, binds)
        val analyze = jdbc.queryForObject("explain analyze $sql", String::class.java, *binds.toTypedArray())

        return """
            |-- $label
            |$sql
            |-- EXPLAIN FORMAT=TREE
            |$tree
            |-- EXPLAIN ANALYZE
            |$analyze
        """.trimMargin()
    }

    /** [sql]의 `EXPLAIN FORMAT=TREE`. 문장을 실행하지 않고 계획만 세운다. */
    private fun explainTree(sql: String, binds: List<Any>): String {
        check(sql.count { it == '?' } == binds.size) { "바인드 값 ${binds.size}개가 SQL의 자리 수와 다르다: $sql" }

        return jdbc.queryForObject("explain format=tree $sql", String::class.java, *binds.toTypedArray())!!
    }

    private fun activeBrandCount(brandId: Long): Int {
        return jdbc.queryForObject(
            "select count(*) from brand where id = ? and deleted_at is null",
            Int::class.java,
            brandId,
        )!!
    }

    private fun activeProductCount(brandId: Long): Int {
        return jdbc.queryForObject(
            "select count(*) from product where brand_id = ? and deleted_at is null",
            Int::class.java,
            brandId,
        )!!
    }

    /** [warmUps]번 보낸 뒤 [samples]번을 하나씩 잰다. 응답의 상태는 잰 시간 밖에서 본다. */
    private fun measureLatency(warmUps: Int, samples: Int, request: () -> MvcTestResult): Latency {
        repeat(warmUps) { assertThat(request()).hasStatusOk() }
        val durations = List(samples) {
            val (result, elapsed) = measureTimedValue(request)
            assertThat(result).hasStatusOk()
            elapsed
        }

        return Latency(warmUps, durations.sorted())
    }

    /** 잰 지연의 분포. 백분위는 가장 가까운 순위(nearest-rank)로 고른다. */
    private class Latency(
        private val warmUps: Int,
        private val sorted: List<Duration>,
    ) {
        private fun percentile(p: Int): Duration {
            return sorted[ceil(p * sorted.size / 100.0).toInt() - 1]
        }

        override fun toString(): String {
            val distribution = listOf(
                "min" to sorted.first(),
                "p50" to percentile(50),
                "p95" to percentile(95),
                "p99" to percentile(99),
                "max" to sorted.last(),
            ).joinToString { (name, duration) -> "$name %.3f ms".format(duration.toDouble(DurationUnit.MILLISECONDS)) }

            return "warm-up $warmUps, samples ${sorted.size}: $distribution"
        }
    }
}
