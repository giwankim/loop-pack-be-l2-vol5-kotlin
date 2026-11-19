package com.loopers.application.ranking

import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.ranking.provided.WeeklyRankingFinder
import com.loopers.application.ranking.provided.WeeklyRankingInfo
import com.loopers.application.ranking.provided.WeeklyRankingItemInfo
import com.loopers.application.ranking.provided.WeeklyRankingFindRequest
import com.loopers.application.ranking.required.WeeklyRankingRepository
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [WeeklyRankingFinder]'s implementation. It validates its Request, so the Monday check reaches a caller without the controller.
 *
 * The header, the items and the products' display fields are read in one read-only transaction. A snapshot's weekly ranking is
 * written with its publication and never changes after, and the products are read in the same transaction as the items, so no
 * item that the items statement returned can lose its product before the third read. Not cached (spec 252).
 */
@ValidatedApplicationService(readOnly = true)
class WeeklyRankingQueryService(
    private val weeklyRankingRepository: WeeklyRankingRepository,
    private val productFinder: ProductFinder,
) : WeeklyRankingFinder {
    override fun find(request: WeeklyRankingFindRequest): WeeklyRankingInfo {
        // Method validation has refused a missing value before this line.
        val periodStart = checkNotNull(request.periodStart)
        val publication = weeklyRankingRepository.findPublication(periodStart)
            ?: return WeeklyRankingInfo.notPublished(periodStart)

        val items = weeklyRankingRepository.findItems(publication.snapshotId, request.limit)
        val products = productFinder.findAllSummaries(items.map { it.productId }).associateBy { it.id }
        val itemInfos = items.map {
            WeeklyRankingItemInfo(rank = it.rank, score = it.score, product = products.getValue(it.productId))
        }

        return WeeklyRankingInfo.published(
            periodStart = periodStart,
            snapshotId = publication.snapshotId,
            inputFrozenAt = publication.inputFrozenAt,
            items = itemInfos,
        )
    }
}
