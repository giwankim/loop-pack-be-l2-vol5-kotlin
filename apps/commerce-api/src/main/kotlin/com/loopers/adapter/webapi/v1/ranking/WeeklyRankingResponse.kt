package com.loopers.adapter.webapi.v1.ranking

import com.fasterxml.jackson.annotation.JsonInclude
import com.loopers.adapter.webapi.v1.product.ProductResponse
import com.loopers.application.ranking.provided.WeeklyRankingInfo
import com.loopers.application.ranking.provided.WeeklyRankingItemInfo
import com.loopers.application.ranking.provided.WeeklyRankingStatus
import java.time.Instant
import java.time.LocalDate

/**
 * The customer's weekly ranking. [periodEnd] is the next Monday, not included. The revision and the publication time are internal
 * and not sent.
 *
 * [snapshotId] and [inputFrozenAt] are written as `null` when nothing is published, instead of left out as the application's
 * default inclusion would: the contract names both fields, and `null` is how it says "no snapshot" (spec 252).
 */
data class WeeklyRankingResponse(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val status: WeeklyRankingStatus,
    @JsonInclude(JsonInclude.Include.ALWAYS)
    val snapshotId: String?,
    @JsonInclude(JsonInclude.Include.ALWAYS)
    val inputFrozenAt: Instant?,
    val items: List<WeeklyRankingItemResponse>,
) {
    companion object {
        fun from(info: WeeklyRankingInfo): WeeklyRankingResponse {
            return WeeklyRankingResponse(
                periodStart = info.periodStart,
                periodEnd = info.periodEnd,
                status = info.status,
                snapshotId = info.snapshotId,
                inputFrozenAt = info.inputFrozenAt,
                items = info.items.map(WeeklyRankingItemResponse::from),
            )
        }
    }
}

/** One item: the rank and score of the published snapshot, and the product as the customer product list shows it. */
data class WeeklyRankingItemResponse(
    val rank: Int,
    val score: Long,
    val product: ProductResponse,
) {
    companion object {
        fun from(info: WeeklyRankingItemInfo): WeeklyRankingItemResponse {
            return WeeklyRankingItemResponse(
                rank = info.rank,
                score = info.score,
                product = ProductResponse.from(info.product),
            )
        }
    }
}
