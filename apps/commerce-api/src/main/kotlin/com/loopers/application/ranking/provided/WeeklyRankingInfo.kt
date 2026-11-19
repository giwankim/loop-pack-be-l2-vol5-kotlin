package com.loopers.application.ranking.provided

import com.loopers.application.product.provided.ProductSummaryInfo
import java.time.Instant
import java.time.LocalDate

/**
 * One ranking period's weekly ranking as a customer sees it: the period, whether anything is published for it, and the published
 * snapshot's items. [periodEnd] is the next Monday, which the period does not include.
 *
 * With nothing published, [snapshotId] and [inputFrozenAt] are null and [items] is empty. A published snapshot with no purchases has
 * its [snapshotId] and no items, so a quiet week reads differently from an unpublished one.
 */
data class WeeklyRankingInfo(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val status: WeeklyRankingStatus,
    val snapshotId: String?,
    val inputFrozenAt: Instant?,
    val items: List<WeeklyRankingItemInfo>,
) {
    companion object {
        fun notPublished(periodStart: LocalDate): WeeklyRankingInfo {
            return WeeklyRankingInfo(
                periodStart = periodStart,
                periodEnd = periodEndOf(periodStart),
                status = WeeklyRankingStatus.NOT_PUBLISHED,
                snapshotId = null,
                inputFrozenAt = null,
                items = emptyList(),
            )
        }

        fun published(
            periodStart: LocalDate,
            snapshotId: String,
            inputFrozenAt: Instant,
            items: List<WeeklyRankingItemInfo>,
        ): WeeklyRankingInfo {
            return WeeklyRankingInfo(
                periodStart = periodStart,
                periodEnd = periodEndOf(periodStart),
                status = WeeklyRankingStatus.PUBLISHED,
                snapshotId = snapshotId,
                inputFrozenAt = inputFrozenAt,
                items = items,
            )
        }

        /** A ranking period is one week; its end is the next period's start and is not part of it. */
        private fun periodEndOf(periodStart: LocalDate): LocalDate {
            return periodStart.plusWeeks(1)
        }
    }
}

/**
 * One item of the weekly ranking: the product's [rank] and [score] in the published snapshot, and its display fields as the
 * customer product list carries them. Ranks are the snapshot's, so a deleted product left out leaves a gap rather than moving the
 * others up.
 */
data class WeeklyRankingItemInfo(
    val rank: Int,
    val score: Long,
    val product: ProductSummaryInfo,
)

/** Whether a ranking period has a publication. A period with none answers [NOT_PUBLISHED], not an error. */
enum class WeeklyRankingStatus {
    PUBLISHED,
    NOT_PUBLISHED,
}
