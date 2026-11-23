package com.loopers.support

import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.HexFormat

/**
 * What the weekly ranking's job tests share: the job's three identifying parameters, purchase activities by SQL,
 * the tables a run writes, and the snapshots, snapshot inputs, scores, ranking items and publications it stored.
 */
object WeeklyRankingJobs {
    /** Every table the weekly ranking's job writes, for a test to empty before each scene. */
    val RANKING_TABLES = arrayOf(
        "weekly_ranking_publication",
        "weekly_ranking_item",
        "weekly_ranking_score",
        "weekly_ranking_snapshot_input",
        "weekly_ranking_snapshot",
    )

    const val INSERT_ACTIVITY_SQL =
        "INSERT INTO purchase_activity (order_id, product_id, quantity, confirmed_at) VALUES (?, ?, ?, ?)"

    fun jobParameters(
        periodStart: LocalDate = LocalDate.parse("2026-08-17"),
        snapshotId: String,
        revision: Long = 1,
    ): JobParameters {
        return JobParametersBuilder()
            .addLocalDate("periodStart", periodStart)
            .addString("snapshotId", snapshotId)
            .addLong("revision", revision)
            .toJobParameters()
    }

    /** Inserts a purchase activity confirmed at [confirmedAt], a UTC wall time, into the test-only `purchase_activity`. */
    fun JdbcClient.insertActivity(orderId: Long, productId: Long, quantity: Int, confirmedAt: LocalDateTime) {
        sql(INSERT_ACTIVITY_SQL)
            .params(orderId, productId, quantity, confirmedAt)
            .update()
    }

    /** Inserts [publication] as it is, such as a previous revision's publication a scene starts from. */
    fun JdbcClient.insertPublication(publication: StoredPublication) {
        sql("INSERT INTO weekly_ranking_publication (period_start, snapshot_id, revision, published_at) VALUES (?, ?, ?, ?)")
            .params(publication.periodStart, publication.snapshotId, publication.revision, publication.publishedAt)
            .update()
    }

    /** The assignment's section 6 orders, inserted in reverse key order so a reader's own order shows. */
    fun JdbcClient.insertAssignmentActivities() {
        WeeklyRankingInputs.ASSIGNMENT_INPUTS.reversed().forEach { input ->
            insertActivity(
                orderId = input.orderId,
                productId = input.productId,
                quantity = input.quantity,
                confirmedAt = LocalDateTime.ofInstant(input.confirmedAt, ZoneOffset.UTC),
            )
        }
    }

    /** Every snapshot row, in snapshot ID order. */
    fun JdbcClient.snapshots(): List<StoredSnapshot> {
        return sql("SELECT * FROM weekly_ranking_snapshot ORDER BY snapshot_id")
            .query { resultSet, _ ->
                StoredSnapshot(
                    snapshotId = resultSet.getString("snapshot_id"),
                    periodStart = resultSet.getObject("period_start", LocalDate::class.java),
                    revision = resultSet.getLong("revision"),
                    state = resultSet.getString("state"),
                    frozenAt = resultSet.getObject("frozen_at", LocalDateTime::class.java),
                    inputCount = resultSet.getLong("input_count"),
                    inputChecksum = resultSet.getString("input_checksum"),
                )
            }
            .list()
    }

    /** The inputs the freeze stored for [snapshotId], in key order. */
    fun JdbcClient.snapshotInputs(snapshotId: String): List<StoredSnapshotInput> {
        return sql(
            """
            SELECT order_id, product_id, quantity, confirmed_at FROM weekly_ranking_snapshot_input
            WHERE snapshot_id = ? ORDER BY order_id, product_id
            """.trimIndent(),
        )
            .param(snapshotId)
            .query { resultSet, _ ->
                StoredSnapshotInput(
                    orderId = resultSet.getLong("order_id"),
                    productId = resultSet.getLong("product_id"),
                    quantity = resultSet.getInt("quantity"),
                    confirmedAt = resultSet.getObject("confirmed_at", LocalDateTime::class.java),
                )
            }
            .list()
    }

    /** The scores the score step stored for [snapshotId], in product order. */
    fun JdbcClient.scores(snapshotId: String): List<StoredScore> {
        return sql("SELECT product_id, score FROM weekly_ranking_score WHERE snapshot_id = ? ORDER BY product_id")
            .param(snapshotId)
            .query { resultSet, _ ->
                StoredScore(productId = resultSet.getLong("product_id"), score = resultSet.getLong("score"))
            }
            .list()
    }

    /** Every snapshot's weekly ranking, by snapshot and then rank. */
    fun JdbcClient.rankingItems(): List<StoredRankingItem> {
        return sql(
            """
            SELECT snapshot_id, product_id, score, product_rank FROM weekly_ranking_item
            ORDER BY snapshot_id, product_rank
            """.trimIndent(),
        )
            .query { resultSet, _ ->
                StoredRankingItem(
                    snapshotId = resultSet.getString("snapshot_id"),
                    productId = resultSet.getLong("product_id"),
                    score = resultSet.getLong("score"),
                    productRank = resultSet.getInt("product_rank"),
                )
            }
            .list()
    }

    /**
     * The result checksum of [snapshotId]'s weekly ranking, in the input checksum's form: the lowercase hex SHA-256 of one line
     * per ranking row in rank order, its product ID, score and rank joined by TAB, every line ended by LF, in UTF-8. Production
     * code neither computes nor stores it (spec 252).
     */
    fun JdbcClient.resultChecksum(snapshotId: String): String {
        val canonicalText = rankingItems()
            .filter { it.snapshotId == snapshotId }
            .joinToString(separator = "") { "${it.productId}\t${it.score}\t${it.productRank}\n" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalText.toByteArray(Charsets.UTF_8))

        return HexFormat.of().formatHex(digest)
    }

    /** Every period's publication, in period order. */
    fun JdbcClient.publications(): List<StoredPublication> {
        return sql(
            """
            SELECT period_start, snapshot_id, revision, published_at FROM weekly_ranking_publication
            ORDER BY period_start
            """.trimIndent(),
        )
            .query { resultSet, _ ->
                StoredPublication(
                    periodStart = resultSet.getObject("period_start", LocalDate::class.java),
                    snapshotId = resultSet.getString("snapshot_id"),
                    revision = resultSet.getLong("revision"),
                    publishedAt = resultSet.getObject("published_at", LocalDateTime::class.java),
                )
            }
            .list()
    }

    data class StoredSnapshot(
        val snapshotId: String,
        val periodStart: LocalDate,
        val revision: Long,
        val state: String,
        val frozenAt: LocalDateTime,
        val inputCount: Long,
        val inputChecksum: String,
    )

    data class StoredSnapshotInput(
        val orderId: Long,
        val productId: Long,
        val quantity: Int,
        val confirmedAt: LocalDateTime,
    )

    data class StoredScore(val productId: Long, val score: Long)

    data class StoredRankingItem(val snapshotId: String, val productId: Long, val score: Long, val productRank: Int)

    data class StoredPublication(
        val periodStart: LocalDate,
        val snapshotId: String,
        val revision: Long,
        val publishedAt: LocalDateTime,
    )
}
