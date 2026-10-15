package com.loopers.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter
import java.sql.PreparedStatement
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 상품 목록을 재는 재현 가능한 카탈로그(과제 2절). 분포는 순번 `i`의 Kotlin 식이라 실행마다 같은 행이 나오고,
 * 식별자는 순번 그대로 넣으므로 기대값을 순번에서 곧바로 셀 수 있다.
 *
 * - 브랜드 1…100. 99와 100은 삭제됐고 그 상품도 모두 삭제됐다(브랜드 삭제의 불변식).
 * - 상품 1…10,000은 과제의 예시를 따른다. 브랜드 `(i % 100) + 1`, 가격 `((i * 37) % 100,000) + 100`이고 가격은 모두 다르다.
 * - 큰 브랜드 101은 상품 10,001…12,000을 가진다. 가격 `((i % 20) + 1) * 1,000`이라 가격마다 약 95개가 동점이다.
 * - 모든 상품: `i % 33 = 0`이면 삭제됨, `i % 10 = 0`이면 재고 0, 그 밖은 `(i % 50) + 1`. 등록 시각은 `i`를 따라 늘어난다.
 * - 사용자 1…1,000.
 * - 좋아요: `i % 4 = 0`이면 없고, 그 밖에 `i % 97 = 0`이면 인기 상품으로 300…900개, 그 밖은 `i % 17`개다.
 *   삭제된 상품에도 좋아요가 남는다(좋아요에는 삭제됨이 없다).
 *
 * 값은 엔티티의 규칙(이름, 가격, 재고)을 지킨다. 생성자를 거치지 않으므로 [ProductQuerySeedTest]가 진짜 생성자에 넣어 본다.
 * 행은 JPA가 아니라 JDBC 배치로 넣는다. `IDENTITY` 식별자는 Hibernate의 JDBC 배치를 끄므로 행마다 왕복이 생긴다.
 */
object ProductQuerySeed {
    /** 과제의 예시를 따르는 상품. 순번 1…10,000. */
    private val ASSIGNMENT_PRODUCTS = 1L..10_000L

    private val BRANDS = 1L..100L

    /** 큰 브랜드. 순번 10,001…12,000의 상품을 모두 가진다. */
    private const val LARGE_BRAND_ID = 101L

    private val LARGE_BRAND_PRODUCTS = 10_001L..12_000L

    private val DELETED_BRAND_IDS = setOf(99L, 100L)

    private const val USER_COUNT = 1_000L

    /** 배치 하나에 담는 행 수. `rewriteBatchedStatements=true`가 배치를 여러 행의 INSERT로 바꾼다(`jpa.yml`). */
    private const val BATCH_SIZE = 1_000

    private val REGISTERED_FROM: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private val LIKED_AT: Instant = REGISTERED_FROM.plusSeconds(43_200)

    private val DELETED_AT: Instant = REGISTERED_FROM.plusSeconds(86_400)

    val brands: List<SeedBrand> by lazy {
        (BRANDS + LARGE_BRAND_ID).map { id ->
            SeedBrand(
                id = id,
                name = "brand-$id",
                createdAt = REGISTERED_FROM,
                deletedAt = DELETED_AT.takeIf { id in DELETED_BRAND_IDS },
            )
        }
    }

    val products: List<SeedProduct> by lazy {
        ASSIGNMENT_PRODUCTS.map { i -> product(i, brandId = (i % 100) + 1, price = ((i * 37) % 100_000) + 100) } +
            LARGE_BRAND_PRODUCTS.map { i -> product(i, brandId = LARGE_BRAND_ID, price = ((i % 20) + 1) * 1_000) }
    }

    private fun product(i: Long, brandId: Long, price: Long): SeedProduct {
        return SeedProduct(
            id = i,
            brandId = brandId,
            name = "product-$i",
            price = price,
            stock = if (i % 10 == 0L) 0 else ((i % 50) + 1).toInt(),
            createdAt = REGISTERED_FROM.plusSeconds(i),
            deletedAt = DELETED_AT.takeIf { i % 33 == 0L || brandId in DELETED_BRAND_IDS },
        )
    }

    val users: List<SeedUser> by lazy {
        (1L..USER_COUNT).map { id -> SeedUser(id = id, createdAt = REGISTERED_FROM) }
    }

    /** 상품 순번 차례로, 한 상품의 좋아요는 순번 `i`부터 이어지는 서로 다른 사용자가 누른다. 식별자는 1부터 차례로 붙는다. */
    val likes: List<SeedLike> by lazy {
        products
            .flatMap { product -> likerIds(product.id).map { userId -> userId to product.id } }
            .mapIndexed { index, (userId, productId) ->
                SeedLike(id = index + 1L, userId = userId, productId = productId, createdAt = LIKED_AT)
            }
    }

    /** 상품 [i]의 좋아요 수. 인기 상품은 순번에 따라 300…900개 가운데 100 단위다. */
    private fun likeCount(i: Long): Long {
        return when {
            i % 4 == 0L -> 0
            i % 97 == 0L -> 300 + ((i / 97) % 7) * 100
            else -> i % 17
        }
    }

    /** 좋아요 수가 사용자 수를 넘지 않으므로 사용자가 한 바퀴를 돌지 않아 겹치지 않는다. */
    private fun likerIds(i: Long): List<Long> {
        return (0 until likeCount(i)).map { k -> ((i + k) % USER_COUNT) + 1 }
    }

    /**
     * 모든 행을 JDBC 배치로 넣고 통계를 새로 만든다. 행은 외래 키가 가리키는 쪽부터 넣는다.
     * `ANALYZE TABLE`은 열린 트랜잭션을 암묵으로 커밋하므로, 부르는 테스트는 테스트 트랜잭션에서 빠져 있어야 한다.
     */
    fun load(jdbc: JdbcTemplate) {
        jdbc.insert(
            "insert into brand (id, name, created_at, updated_at, deleted_at) values (?, ?, ?, ?, ?)",
            brands,
        ) { brand ->
            setLong(1, brand.id)
            setString(2, brand.name)
            setObject(3, brand.createdAt.utc())
            setObject(4, brand.createdAt.utc())
            setObject(5, brand.deletedAt?.utc())
        }
        jdbc.insert(
            "insert into product (id, brand_id, name, price, stock, created_at, updated_at, deleted_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
            products,
        ) { product ->
            setLong(1, product.id)
            setLong(2, product.brandId)
            setString(3, product.name)
            setLong(4, product.price)
            setInt(5, product.stock)
            setObject(6, product.createdAt.utc())
            setObject(7, product.createdAt.utc())
            setObject(8, product.deletedAt?.utc())
        }
        jdbc.insert("insert into users (id, created_at, updated_at) values (?, ?, ?)", users) { user ->
            setLong(1, user.id)
            setObject(2, user.createdAt.utc())
            setObject(3, user.createdAt.utc())
        }
        jdbc.insert(
            "insert into likes (id, user_id, product_id, created_at, updated_at) values (?, ?, ?, ?, ?)",
            likes,
        ) { like ->
            setLong(1, like.id)
            setLong(2, like.userId)
            setLong(3, like.productId)
            setObject(4, like.createdAt.utc())
            setObject(5, like.createdAt.utc())
        }
        analyze(jdbc)
    }

    /** `ANALYZE TABLE`은 실패를 예외가 아니라 결과 행(`Msg_type = error`)으로 알리므로 결과를 본다. */
    private fun analyze(jdbc: JdbcTemplate) {
        val results = jdbc.queryForList("analyze table brand, product, likes, users")
        val errors = results.filter { it["Msg_type"] == "error" }

        check(errors.isEmpty()) { "ANALYZE TABLE failed: $errors" }
    }

    private fun <T : Any> JdbcTemplate.insert(sql: String, rows: List<T>, bind: PreparedStatement.(T) -> Unit) {
        batchUpdate(sql, rows, BATCH_SIZE, ParameterizedPreparedStatementSetter<T> { statement, row -> statement.bind(row) })
    }

    /**
     * 열은 UTC 시각을 담는다(`hibernate.jdbc.time_zone`). [LocalDateTime]은 드라이버가 시간대를 바꾸지 않고 그대로 보내므로
     * UTC의 벽시계 시각으로 옮겨 넘긴다. JVM 시간대(테스트 JVM은 Asia/Seoul)로 옮기면 9시간 늦게 읽힌다.
     */
    private fun Instant.utc(): LocalDateTime {
        return LocalDateTime.ofInstant(this, ZoneOffset.UTC)
    }
}

data class SeedBrand(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

data class SeedProduct(
    val id: Long,
    val brandId: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

data class SeedUser(
    val id: Long,
    val createdAt: Instant,
)

data class SeedLike(
    val id: Long,
    val userId: Long,
    val productId: Long,
    val createdAt: Instant,
)
