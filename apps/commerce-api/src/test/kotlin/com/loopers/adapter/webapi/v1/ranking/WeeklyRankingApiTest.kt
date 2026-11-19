package com.loopers.adapter.webapi.v1.ranking

import com.loopers.domain.product.Product
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The customer's weekly ranking. The batch is not run: the three ranking tables come from a test-only SQL file holding the
 * contract's columns, and each scene writes their rows in SQL as the batch would leave them, since commerce-api has no writer for
 * them (spec 252). The rows join the test transaction and are rolled back with it. No principal is sent, since the read is public.
 *
 * The script runs before the class, outside any test transaction, because `CREATE TABLE` commits in MySQL.
 */
@Sql(scripts = ["/sql/weekly-ranking-tables.sql"], executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class WeeklyRankingApiTest(
    private val jdbc: JdbcTemplate,
) : BaseWebApiAdapterTest() {
    companion object {
        private const val ENDPOINT = "/api/v1/rankings/weekly"
        private val PERIOD_START = LocalDate.parse("2026-08-17")
        private const val SNAPSHOT_ID = "weekly-2026-08-17-r1"
        private val FROZEN_AT = Instant.parse("2026-08-24T00:05:00Z")
    }

    @Test
    fun `the assignment's published ranking puts product 7 first, product 9 second and product 8 third`() {
        val (seven, eight, nine) = insertAssignmentRanking()

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.periodStart").isEqualTo("2026-08-17")
        body.extractingPath("$.data.periodEnd").isEqualTo("2026-08-24")
        body.extractingPath("$.data.status").isEqualTo("PUBLISHED")
        body.extractingPath("$.data.snapshotId").isEqualTo(SNAPSHOT_ID)
        body.extractingPath("$.data.inputFrozenAt").isEqualTo("2026-08-24T00:05:00Z")
        body.extractingPath("$.data.items.length()").isEqualTo(3)
        body.extractingPath("$.data.items[0].rank").isEqualTo(1)
        body.extractingPath("$.data.items[0].score").isEqualTo(5)
        body.extractingPath("$.data.items[0].product.id").isEqualToLong(seven.id)
        body.extractingPath("$.data.items[0].product.name").isEqualTo("상품 7")
        body.extractingPath("$.data.items[0].product.brand.name").isEqualTo("루퍼스")
        body.extractingPath("$.data.items[0].product.soldOut").isEqualTo(false)
        body.extractingPath("$.data.items[0].product.likeCount").isEqualTo(0)
        body.extractingPath("$.data.items[1].rank").isEqualTo(2)
        body.extractingPath("$.data.items[1].score").isEqualTo(5)
        body.extractingPath("$.data.items[1].product.id").isEqualToLong(nine.id)
        body.extractingPath("$.data.items[2].rank").isEqualTo(3)
        body.extractingPath("$.data.items[2].score").isEqualTo(1)
        body.extractingPath("$.data.items[2].product.id").isEqualToLong(eight.id)
    }

    @Test
    fun `a limit of 2 returns ranks 1 and 2`() {
        insertAssignmentRanking()

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17", "limit" to "2")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[0].rank").isEqualTo(1)
        body.extractingPath("$.data.items[1].rank").isEqualTo(2)
    }

    /** Eleven ranked products, so a default of anything above 10 would return the eleventh. */
    @Test
    fun `without a limit the first 10 items are returned`() {
        prepareBrand()
        insertSnapshot(SNAPSHOT_ID)
        (1..11).forEach { rank ->
            insertItem(SNAPSHOT_ID, prepareProduct(brand), score = 100L - rank, rank = rank)
        }
        insertPublication(SNAPSHOT_ID)
        entityManager.flushAndClear()

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(10)
        body.extractingPath("$.data.items[9].rank").isEqualTo(10)
    }

    @Test
    fun `a deleted product is left out and the others keep their ranks`() {
        val (seven, eight, nine) = insertAssignmentRanking()
        deleteProduct(seven)
        entityManager.flushAndClear()

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[0].rank").isEqualTo(2)
        body.extractingPath("$.data.items[0].product.id").isEqualToLong(nine.id)
        body.extractingPath("$.data.items[1].rank").isEqualTo(3)
        body.extractingPath("$.data.items[1].product.id").isEqualToLong(eight.id)
    }

    /** The limit counts after deleted products are left out: two remain, so a limit of 2 gets both. */
    @Test
    fun `with a deleted product a limit of 2 still returns two items`() {
        val (seven, _, _) = insertAssignmentRanking()
        deleteProduct(seven)
        entityManager.flushAndClear()

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17", "limit" to "2")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[0].rank").isEqualTo(2)
        body.extractingPath("$.data.items[1].rank").isEqualTo(3)
    }

    /** A product of another brand is ranked fourth, so the brand's deletion has something left to show. */
    @Test
    fun `a deleted brand's products are all left out`() {
        val (_, eight, _) = insertAssignmentRanking()
        val other = prepareProduct(prepareBrand(name = "다른 브랜드"))
        insertItem(SNAPSHOT_ID, other, score = 1, rank = 4)
        deleteBrand(eight.brand)
        entityManager.flushAndClear()

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].rank").isEqualTo(4)
        body.extractingPath("$.data.items[0].product.id").isEqualToLong(other.id)
    }

    @Test
    fun `a period with no publication is NOT_PUBLISHED with no snapshot and no items`() {
        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.periodStart").isEqualTo("2026-08-17")
        body.extractingPath("$.data.periodEnd").isEqualTo("2026-08-24")
        body.extractingPath("$.data.status").isEqualTo("NOT_PUBLISHED")
        body.extractingPath("$.data.snapshotId").isNull()
        body.extractingPath("$.data.inputFrozenAt").isNull()
        body.extractingPath("$.data.items").asArray().isEmpty()
    }

    @Test
    fun `an empty published snapshot is PUBLISHED with its snapshot ID and no items`() {
        insertSnapshot(SNAPSHOT_ID)
        insertPublication(SNAPSHOT_ID)

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("PUBLISHED")
        body.extractingPath("$.data.snapshotId").isEqualTo(SNAPSHOT_ID)
        body.extractingPath("$.data.inputFrozenAt").isEqualTo("2026-08-24T00:05:00Z")
        body.extractingPath("$.data.items").asArray().isEmpty()
    }

    @Test
    fun `another period's publication is never returned`() {
        insertAssignmentRanking()

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-24")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.periodStart").isEqualTo("2026-08-24")
        body.extractingPath("$.data.status").isEqualTo("NOT_PUBLISHED")
        body.extractingPath("$.data.items").asArray().isEmpty()
    }

    /**
     * Revision 2 of the same period is frozen and ranked but not published, as while its publish has not run or has failed. Its
     * items rank product 8 first, so reading by period instead of by the publication's snapshot ID would show it.
     */
    @Test
    fun `the items of a ranked but unpublished snapshot of the same period are never returned`() {
        val (seven, eight, nine) = insertAssignmentRanking()
        val unpublished = "weekly-2026-08-17-r2"
        insertSnapshot(unpublished, frozenAt = Instant.parse("2026-08-25T00:05:00Z"))
        insertItem(unpublished, eight, score = 9, rank = 1)
        insertItem(unpublished, seven, score = 5, rank = 2)
        insertItem(unpublished, nine, score = 5, rank = 3)

        val body = assertThat(requestGetWeeklyRanking("periodStart" to "2026-08-17")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.snapshotId").isEqualTo(SNAPSHOT_ID)
        body.extractingPath("$.data.items.length()").isEqualTo(3)
        body.extractingPath("$.data.items[0].product.id").isEqualToLong(seven.id)
        body.extractingPath("$.data.items[1].product.id").isEqualToLong(nine.id)
        body.extractingPath("$.data.items[2].product.id").isEqualToLong(eight.id)
    }

    @Test
    fun `a missing periodStart returns 400`() {
        assertBadRequest(requestGetWeeklyRanking(), "periodStart는 필수입니다.")
    }

    @Test
    fun `a periodStart that is not a date returns 400`() {
        assertBadRequest(requestGetWeeklyRanking("periodStart" to "2026-08-32"), "'periodStart'의 값 '2026-08-32'")
    }

    @Test
    fun `a periodStart that is not a Monday returns 400`() {
        assertBadRequest(requestGetWeeklyRanking("periodStart" to "2026-08-18"), "periodStart는 월요일이어야 합니다.")
    }

    @Test
    fun `a limit of 0 returns 400`() {
        assertBadRequest(requestGetWeeklyRanking("periodStart" to "2026-08-17", "limit" to "0"), "limit는 1 이상이어야 합니다.")
    }

    @Test
    fun `a limit of 101 returns 400`() {
        assertBadRequest(requestGetWeeklyRanking("periodStart" to "2026-08-17", "limit" to "101"), "limit는 100 이하여야 합니다.")
    }

    /** Every refusal is the generic bad request, and its message names the cause, [cause]. */
    private fun assertBadRequest(result: MvcTestResult, cause: String) {
        val error = assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.result").isEqualTo("FAIL")
        error.extractingPath("$.meta.errorCode").isEqualTo(ErrorType.BAD_REQUEST.code)
        error.extractingPath("$.meta.message").asString().contains(cause)
    }

    /**
     * The assignment's section 6 ranking of [PERIOD_START], published: product 7 first and product 9 second at 5, product 8 third at 1.
     * The products are registered in the test, so their IDs are not 7, 8 and 9; the names carry the assignment's numbers.
     */
    private fun insertAssignmentRanking(): Triple<Product, Product, Product> {
        prepareBrand(name = "루퍼스")
        val seven = prepareProduct(brand, name = "상품 7")
        val eight = prepareProduct(brand, name = "상품 8")
        val nine = prepareProduct(brand, name = "상품 9")
        insertSnapshot(SNAPSHOT_ID)
        insertItem(SNAPSHOT_ID, seven, score = 5, rank = 1)
        insertItem(SNAPSHOT_ID, nine, score = 5, rank = 2)
        insertItem(SNAPSHOT_ID, eight, score = 1, rank = 3)
        insertPublication(SNAPSHOT_ID)
        entityManager.flushAndClear()

        return Triple(seven, eight, nine)
    }

    /**
     * A snapshot of [PERIOD_START]. The batch writes UTC wall-clock times (its `JdbcUtcTimes`), and a [LocalDateTime] goes to the
     * driver unchanged, so [frozenAt] is moved to UTC first.
     */
    private fun insertSnapshot(snapshotId: String, frozenAt: Instant = FROZEN_AT) {
        jdbc.update(
            "insert into weekly_ranking_snapshot (snapshot_id, period_start, frozen_at) values (?, ?, ?)",
            snapshotId,
            PERIOD_START,
            LocalDateTime.ofInstant(frozenAt, ZoneOffset.UTC),
        )
    }

    private fun insertItem(snapshotId: String, product: Product, score: Long, rank: Int) {
        jdbc.update(
            "insert into weekly_ranking_item (snapshot_id, product_id, score, product_rank) values (?, ?, ?, ?)",
            snapshotId,
            product.id,
            score,
            rank,
        )
    }

    /** Makes [snapshotId] what [PERIOD_START] shows. */
    private fun insertPublication(snapshotId: String) {
        jdbc.update("insert into weekly_ranking_publication (period_start, snapshot_id) values (?, ?)", PERIOD_START, snapshotId)
    }

    private fun requestGetWeeklyRanking(vararg query: Pair<String, String>): MvcTestResult {
        return mvc.get().uri(ENDPOINT)
            .apply { query.forEach { (name, value) -> param(name, value) } }
            .exchange()
    }
}
