package com.loopers.adapter.webapi.v1.ranking

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.application.ranking.provided.WeeklyRankingFindRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Ranking V1 API", description = "고객 랭킹 API 입니다. 식별이 필요 없습니다.")
interface WeeklyRankingApiSpec {
    @Operation(
        summary = "주간 랭킹 조회",
        description = "한 랭킹 기간에 게시된 주간 랭킹을 순위 차례로 조회합니다. periodStart는 랭킹 기간이 시작하는 월요일(UTC)이며 " +
            "반드시 주어야 합니다. 없거나, 날짜가 아니거나, 월요일이 아니면 400입니다. limit는 1 이상 100 이하이고 주지 않으면 10입니다. " +
            "게시가 없는 랭킹 기간은 status가 NOT_PUBLISHED이고 snapshotId와 inputFrozenAt이 null이며 items가 비어 있습니다. " +
            "삭제된 상품은 빠지고 남은 상품의 순위는 그대로이며, limit는 빠진 뒤에 셉니다. periodEnd는 들지 않는 끝(다음 월요일)입니다.",
    )
    fun getWeeklyRanking(
        request: WeeklyRankingFindRequest,
    ): ApiResponse<WeeklyRankingResponse>
}
