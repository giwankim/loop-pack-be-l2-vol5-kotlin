package com.loopers.batch.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * The `score` step's SQL apart from the reader's: one product's sum over a snapshot's inputs, and the score table's writes.
 * It never reads `purchase_activity`, so a purchase recorded after the freeze cannot enter a score. Each call joins the
 * caller's transaction, the chunk's.
 */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
class WeeklyRankingScoreStore(
    private val jdbcClient: JdbcClient,
) {
    /**
     * [productId]'s quantity summed over [snapshotId]'s inputs (`CONTEXT.md` 점수). The product comes from the same inputs,
     * so the sum is never empty.
     */
    fun sumQuantity(snapshotId: String, productId: Long): Long {
        return jdbcClient
            .sql(
                """
                SELECT SUM(quantity)
                FROM weekly_ranking_snapshot_input
                WHERE snapshot_id = ? AND product_id = ?
                """.trimIndent(),
            )
            .params(snapshotId, productId)
            .query(Long::class.java)
            .single()
    }

    /**
     * Stores each of [scores] as [snapshotId]'s score of its product, replacing a stored one with the computed value rather
     * than adding to it, so a product scored twice keeps the same score. All of [scores] go in one statement, a row each, so
     * the caller's chunk size is also the bound on the statement's size.
     */
    fun saveScores(snapshotId: String, scores: List<ProductScore>) {
        if (scores.isEmpty()) {
            return
        }

        val rowPlaceholders = scores.joinToString(", ") { "(?, ?, ?)" }
        val params = scores.flatMap { productScore -> listOf(snapshotId, productScore.productId, productScore.score) }

        jdbcClient
            .sql(
                """
                INSERT INTO weekly_ranking_score (snapshot_id, product_id, score)
                VALUES $rowPlaceholders AS computed
                ON DUPLICATE KEY UPDATE score = computed.score
                """.trimIndent(),
            )
            .params(params)
            .update()
    }
}
