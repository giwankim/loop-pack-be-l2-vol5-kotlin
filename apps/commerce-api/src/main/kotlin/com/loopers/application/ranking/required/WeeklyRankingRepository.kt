package com.loopers.application.ranking.required

import java.time.Instant
import java.time.LocalDate

/**
 * The weekly ranking's tables, which commerce-batch writes and commerce only reads (the contract in CONTEXT-MAP.md). There is no
 * entity: commerce-api's schema generation would drop and recreate a mapped table on every start.
 *
 * The read is two statements, the header and then the items by the header's snapshot ID, so the header is known even when no item
 * is visible and the items never come from another snapshot. Both run in the caller's transaction.
 */
interface WeeklyRankingRepository {
    /** The snapshot [periodStart]'s publication points to, or null when nothing is published for it. */
    fun findPublication(periodStart: LocalDate): WeeklyRankingPublication?

    /**
     * [snapshotId]'s first [limit] items in rank order, of products that are not deleted. Deleted products are filtered before the
     * limit, in the same statement, so the limit counts only what is returned.
     */
    fun findItems(snapshotId: String, limit: Int): List<WeeklyRankingItem>
}

/** A ranking period's publication: the snapshot it points to and when that snapshot's input was frozen. */
data class WeeklyRankingPublication(
    val snapshotId: String,
    val inputFrozenAt: Instant,
)

/** One row of a snapshot's weekly ranking. */
data class WeeklyRankingItem(
    val productId: Long,
    val score: Long,
    val rank: Int,
)
