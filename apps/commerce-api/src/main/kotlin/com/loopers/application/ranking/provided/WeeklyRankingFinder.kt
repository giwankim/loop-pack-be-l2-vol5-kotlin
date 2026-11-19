package com.loopers.application.ranking.provided

import jakarta.validation.Valid

/**
 * The read the ranking slice offers: a ranking period's published weekly ranking. Commerce has no rules of its own here. The weekly
 * ranking context (commerce-batch) decides the ranks and what a period shows; commerce shows it (CONTEXT-MAP.md).
 */
interface WeeklyRankingFinder {
    /**
     * The weekly ranking that [WeeklyRankingFindRequest.periodStart]'s publication points to, its first
     * [WeeklyRankingFindRequest.limit] items in rank order. Deleted products are left out before the limit, and the others keep
     * their ranks. A period with no publication is [WeeklyRankingStatus.NOT_PUBLISHED] with no items.
     */
    fun find(@Valid request: WeeklyRankingFindRequest): WeeklyRankingInfo
}
