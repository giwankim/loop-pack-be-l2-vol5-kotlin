package com.loopers.batch.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.support.time.JdbcUtcTimes
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The freeze's SQL: the weekly ranking's two tables and its one read of `purchase_activity`, by the contract's columns only
 * (`CONTEXT-MAP.md`). Each call joins the caller's transaction.
 */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
class WeeklyRankingSnapshotStore(
    private val jdbcClient: JdbcClient,
) {
    /**
     * The snapshots that hold [key]'s snapshot ID, or its period and revision: at most two, one per unique key. The read takes no
     * lock. Two freezes racing past it are still refused by the keys themselves, when the later one writes its snapshot.
     */
    fun findConflicting(key: SnapshotKey): List<SnapshotKey> {
        return jdbcClient
            .sql(
                """
                SELECT snapshot_id, period_start, revision
                FROM weekly_ranking_snapshot
                WHERE snapshot_id = ? OR (period_start = ? AND revision = ?)
                ORDER BY snapshot_id
                """.trimIndent(),
            )
            .params(key.snapshotId, key.periodStart, key.revision)
            .query { resultSet, _ ->
                SnapshotKey(
                    snapshotId = resultSet.getString("snapshot_id"),
                    periodStart = resultSet.getObject("period_start", LocalDate::class.java),
                    revision = resultSet.getLong("revision"),
                )
            }
            .list()
    }

    /**
     * Copies every purchase activity confirmed in `[periodStart, periodEnd)` and not deleted into [snapshotId]'s inputs, in
     * one statement, so every input comes from one read.
     */
    fun copyInputs(snapshotId: String, periodStart: Instant, periodEnd: Instant) {
        jdbcClient
            .sql(
                """
                INSERT INTO weekly_ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
                SELECT ?, order_id, product_id, quantity, confirmed_at
                FROM purchase_activity
                WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL
                """.trimIndent(),
            )
            .params(snapshotId, JdbcUtcTimes.toJdbc(periodStart), JdbcUtcTimes.toJdbc(periodEnd))
            .update()
    }

    /**
     * Reads [snapshotId]'s inputs back in `order_id`, `product_id` order into a digest, one row at a time. The fetch size asks
     * Connector/J to stream the rows rather than load the whole result first, so a week of inputs is never held in memory.
     */
    fun digestInputs(snapshotId: String): SnapshotInputTotals {
        val digest = SnapshotInputDigest()
        jdbcClient
            .sql(
                """
                SELECT order_id, product_id, quantity, confirmed_at
                FROM weekly_ranking_snapshot_input
                WHERE snapshot_id = ?
                ORDER BY order_id, product_id
                """.trimIndent(),
            )
            .param(snapshotId)
            .withFetchSize(Int.MIN_VALUE)
            .query { resultSet ->
                digest.add(
                    SnapshotInput(
                        orderId = resultSet.getLong("order_id"),
                        productId = resultSet.getLong("product_id"),
                        quantity = resultSet.getInt("quantity"),
                        confirmedAt = JdbcUtcTimes.fromJdbc(resultSet.getObject("confirmed_at", LocalDateTime::class.java)),
                    ),
                )
            }

        return SnapshotInputTotals(count = digest.count, checksum = digest.checksum())
    }

    /** Stores the snapshot as `FROZEN`, the one state this step writes. */
    fun insertFrozenSnapshot(key: SnapshotKey, frozenAt: Instant, totals: SnapshotInputTotals) {
        jdbcClient
            .sql(
                """
                INSERT INTO weekly_ranking_snapshot
                    (snapshot_id, period_start, revision, state, frozen_at, input_count, input_checksum)
                VALUES (?, ?, ?, 'FROZEN', ?, ?, ?)
                """.trimIndent(),
            )
            .params(key.snapshotId, key.periodStart, key.revision, JdbcUtcTimes.toJdbc(frozenAt), totals.count, totals.checksum)
            .update()
    }
}

/** The count and checksum of one snapshot's inputs. */
data class SnapshotInputTotals(val count: Long, val checksum: String)
