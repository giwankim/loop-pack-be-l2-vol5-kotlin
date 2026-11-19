package com.loopers.adapter.persistence.ranking

import com.loopers.application.ranking.required.WeeklyRankingItem
import com.loopers.application.ranking.required.WeeklyRankingPublication
import com.loopers.application.ranking.required.WeeklyRankingRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * [WeeklyRankingRepository] in plain SQL over commerce-batch's tables, by the contract's columns only (CONTEXT-MAP.md). No JPA
 * entity maps them: with `ddl-auto: create` the API would drop and recreate the table on every start. `JdbcClient` takes the
 * connection of the caller's JPA transaction, so both statements read in it.
 */
@Component
class JdbcWeeklyRankingRepository(
    private val jdbcClient: JdbcClient,
) : WeeklyRankingRepository {
    /**
     * The snapshot is joined by its ID and its period both: a publication pointing at another period's snapshot is not this period's
     * ranking, so it reads as nothing published rather than as another week.
     *
     * The batch writes `frozen_at` as a UTC wall-clock time (its `JdbcUtcTimes`), and Connector/J returns a [LocalDateTime]
     * unchanged, so the column is read back at UTC.
     */
    override fun findPublication(periodStart: LocalDate): WeeklyRankingPublication? {
        return jdbcClient
            .sql(
                """
                SELECT publication.snapshot_id, snapshot.frozen_at
                FROM weekly_ranking_publication publication
                JOIN weekly_ranking_snapshot snapshot
                    ON snapshot.snapshot_id = publication.snapshot_id AND snapshot.period_start = publication.period_start
                WHERE publication.period_start = ?
                """.trimIndent(),
            )
            .param(periodStart)
            .query { resultSet, _ ->
                WeeklyRankingPublication(
                    snapshotId = resultSet.getString("snapshot_id"),
                    inputFrozenAt = resultSet.getObject("frozen_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
                )
            }
            .optional()
            .orElse(null)
    }

    /**
     * `deleted_at IS NULL` restates [com.loopers.domain.product.Product]'s `@SQLRestriction` in the one place SQL reads `product`
     * outside the entity. The filter has to be in this statement for `LIMIT` to count only what is kept. The brand is not joined:
     * deleting a brand deletes its products (ADR 0017). The primary key `(snapshot_id, product_rank)` serves the order.
     */
    override fun findItems(snapshotId: String, limit: Int): List<WeeklyRankingItem> {
        return jdbcClient
            .sql(
                """
                SELECT item.product_id, item.score, item.product_rank
                FROM weekly_ranking_item item
                JOIN product ON product.id = item.product_id AND product.deleted_at IS NULL
                WHERE item.snapshot_id = ?
                ORDER BY item.product_rank
                LIMIT ?
                """.trimIndent(),
            )
            .params(snapshotId, limit)
            .query { resultSet, _ ->
                WeeklyRankingItem(
                    productId = resultSet.getLong("product_id"),
                    score = resultSet.getLong("score"),
                    rank = resultSet.getInt("product_rank"),
                )
            }
            .list()
    }
}
