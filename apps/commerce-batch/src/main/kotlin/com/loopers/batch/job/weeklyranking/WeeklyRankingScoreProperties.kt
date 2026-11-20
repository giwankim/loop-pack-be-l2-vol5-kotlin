package com.loopers.batch.job.weeklyranking

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `score` step's settings. The value is in `application.yml` under `weekly-ranking.score`.
 *
 * [chunkSize] is how many products one chunk reads, scores and commits together with the reader's position, and so how many
 * rows the writer's one statement carries. The default of 100 was not measured.
 */
@ConfigurationProperties("weekly-ranking.score")
data class WeeklyRankingScoreProperties(
    val chunkSize: Int,
) {
    init {
        require(chunkSize >= 1) { "weekly-ranking.score.chunk-size must be at least 1, but was $chunkSize" }
    }
}
