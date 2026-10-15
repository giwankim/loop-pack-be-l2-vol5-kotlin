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
 * 실행 계획·지연을 잰다. 기준선은 지금의 스키마다(카탈로그 설계 8).
 *
 * seed를 넣은 뒤의 `ANALYZE TABLE`이 열린 트랜잭션을 암묵으로 커밋하므로 롤백으로 정리할 수 없다. 그래서 클래스는 테스트 트랜잭션에서
 * 빠지고, seed는 클래스마다 한 번 넣어 끝나면 [DatabaseCleanUp]으로 표를 비운다. 수만 행을 테스트마다 다시 넣지 않으려고 PER_CLASS다.
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

        /** 과제 예시의 브랜드 1…100 가운데 하나. 살아 있는 상품이 97개라 첫 페이지 뒤에도 상품이 남는다. */
        private const val BRAND_ID = 7L

        private const val PRICE_ASC = "price_asc"

        private const val PAGE_SIZE = 20
    }

    /** 브랜드 삭제를 감싸는 바깥 트랜잭션. 서비스의 트랜잭션이 여기에 합류하고, 테스트가 롤백만 하도록 표시한다. */
    private val transaction = TransactionTemplate(transactionManager)

    @BeforeAll
    fun loadSeed() {
        ProductQuerySeed.load(jdbc)
    }

    @AfterAll
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `a brand's price_asc first page lists the seed's live products of that brand cheapest first`() {
        val expected = expectedFirstPageBody(priceAscCandidates(BRAND_ID))

        assertThat(requestGetProducts(BRAND_ID, PRICE_ASC)).hasStatusOk().bodyJson().isStrictlyEqualTo(expected)
    }

    /** 페이지 쿼리 하나와 좋아요 수 배치 조회 하나다. 총 개수를 세거나 항목마다 좋아요를 세기 시작하면 늘어난다(설계 5.5, 5.28). */
    @Test
    fun `a brand's price_asc page request sends two SQL statements`() {
        entityManager.withStatistics { statistics ->
            assertThat(requestGetProducts(BRAND_ID, PRICE_ASC)).hasStatusOk()

            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        }
    }

    /**
     * 기준선을 출력한다. 요청이 실제로 보낸 두 문장을 잡아 같은 바인드 값으로 EXPLAIN하고, 준비 요청 뒤에 지연을 잰다.
     * 문장이 둘이라는 단언은 바인드 값을 문장에 맞추려는 것이고, 시간이나 계획의 모양은 단언하지 않는다.
     * 좋아요 수 배치 조회의 바인드 값은 첫 페이지의 상품 id다. 응답이 그 페이지와 같다는 것은 첫 테스트가 지킨다.
     */
    @Test
    fun `the brand price_asc baseline prints the plans of the statements the request sent and its latency`() {
        val captured = captureSql { requestGetProducts(BRAND_ID, PRICE_ASC) }
        assertThat(captured.result).hasStatusOk()
        assertThat(captured.statements).hasSize(2)
        val (pageQuery, likeCountQuery) = captured.statements
        val pageIds = priceAscCandidates(BRAND_ID).take(PAGE_SIZE).map { it.id }

        val latency = measureLatency(warmUps = 50, samples = 200) { requestGetProducts(BRAND_ID, PRICE_ASC) }

        log.info {
            """
            |brand $BRAND_ID $PRICE_ASC page 0 size $PAGE_SIZE
            |${plans("page query", pageQuery, listOf(BRAND_ID, 0, PAGE_SIZE + 1))}
            |${plans("like count query", likeCountQuery, pageIds)}
            |latency $latency
            """.trimMargin()
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

    private fun requestGetProducts(brandId: Long, sort: String): MvcTestResult {
        return mvc.get().uri(ENDPOINT)
            .queryParam("brandId", brandId.toString())
            .queryParam("sort", sort)
            .exchange()
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
     * [candidates]의 첫 페이지를 응답 본문 그대로 손으로 적는다. 좋아요 수는 seed의 좋아요를 상품마다 센다.
     * 엄격하게 견주므로 값뿐 아니라 빠지거나 더 실린 필드, JSON 타입, 항목의 차례가 모두 걸린다.
     */
    private fun expectedFirstPageBody(candidates: List<SeedProduct>): String {
        val likeCounts = ProductQuerySeed.likes.groupingBy { it.productId }.eachCount()
        val brandNames = ProductQuerySeed.brands.associate { it.id to it.name }
        val items = candidates.take(PAGE_SIZE).joinToString(",") { product ->
            """{"id":${product.id},"name":"${product.name}","price":${product.price},"soldOut":${product.stock == 0},""" +
                """"brand":{"id":${product.brandId},"name":"${brandNames.getValue(product.brandId)}"},""" +
                """"likeCount":${likeCounts[product.id] ?: 0}}"""
        }
        val hasNext = candidates.size > PAGE_SIZE

        return """{"meta":{"result":"SUCCESS"},"data":{"items":[$items],"page":0,"size":$PAGE_SIZE,"hasNext":$hasNext}}"""
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
