package com.loopers.adapter.webapi.v1.ranking

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.application.ranking.provided.WeeklyRankingFinder
import com.loopers.application.ranking.provided.WeeklyRankingFindRequest
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping

/** The customer's weekly ranking. Public like the product list: it takes no requester. */
@WebApiAdapter
@RequestMapping("/api/v1/rankings")
class WeeklyRankingApi(
    private val weeklyRankingFinder: WeeklyRankingFinder,
) : WeeklyRankingApiSpec {
    /** The query string is bound to [WeeklyRankingFindRequest] directly, as the product list does (design 5.17). */
    @GetMapping("/weekly")
    override fun getWeeklyRanking(
        @ModelAttribute @Valid request: WeeklyRankingFindRequest,
    ): ApiResponse<WeeklyRankingResponse> {
        val ranking = weeklyRankingFinder.find(request)

        return ApiResponse.success(WeeklyRankingResponse.from(ranking))
    }
}
