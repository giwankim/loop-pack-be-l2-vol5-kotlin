package com.loopers.support

import com.loopers.support.test.BaseCommittingApplicationServiceTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.time.measureTime

private val log = KotlinLogging.logger {}

/**
 * [ProductQuerySeed]를 Testcontainers MySQL에 넣고 표에서 다시 센다. 분포 자체는 [ProductQuerySeedTest]가 DB 없이 고정하고,
 * 여기서는 넣은 행이 seed의 행과 같은지만 본다.
 *
 * 넣은 뒤의 `ANALYZE TABLE`이 열린 트랜잭션을 암묵으로 커밋하므로 롤백으로 정리할 수 없다. 그래서 커밋하는 기반을 상속해
 * 테스트 트랜잭션에서 빠지고, 기반이 테스트가 끝날 때 모든 표를 비운다.
 */
class ProductQuerySeedLoadTest(
    private val jdbc: JdbcTemplate,
) : BaseCommittingApplicationServiceTest() {
    @Test
    fun `loading writes every seeded row, and the active counts leave out deleted brands and products`() {
        val elapsed = measureTime { ProductQuerySeed.load(jdbc) }
        log.info { "ProductQuerySeed loaded in $elapsed" }

        assertThat(count("select count(*) from brand")).isEqualTo(ProductQuerySeed.brands.size)
        assertThat(count("select count(*) from product")).isEqualTo(ProductQuerySeed.products.size)
        assertThat(count("select count(*) from users")).isEqualTo(ProductQuerySeed.users.size)
        assertThat(count("select count(*) from likes")).isEqualTo(ProductQuerySeed.likes.size)
        assertThat(count("select count(*) from brand where deleted_at is null"))
            .isEqualTo(ProductQuerySeed.brands.count { it.deletedAt == null })
        assertThat(activeProductCountsByBrand())
            .isEqualTo(ProductQuerySeed.products.filter { it.deletedAt == null }.groupingBy { it.brandId }.eachCount())
    }

    @Test
    fun `loaded timestamps read back as the seeded instants`() {
        ProductQuerySeed.load(jdbc)
        val deleted = ProductQuerySeed.products.first { it.deletedAt != null }

        val deletedAt = inNewTransaction { entityManager.productDeletedAt(deleted.id) }

        assertThat(deletedAt).isEqualTo(deleted.deletedAt)
    }

    private fun count(sql: String): Int {
        return jdbc.queryForObject(sql, Int::class.java)!!
    }

    private fun activeProductCountsByBrand(): Map<Long, Int> {
        val rows = jdbc.queryForList(
            "select brand_id, count(*) as active from product where deleted_at is null group by brand_id",
        )

        return rows.associate { (it["brand_id"] as Number).toLong() to (it["active"] as Number).toInt() }
    }
}
