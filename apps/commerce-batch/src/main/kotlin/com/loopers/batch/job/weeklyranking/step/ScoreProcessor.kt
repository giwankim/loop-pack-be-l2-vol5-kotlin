package com.loopers.batch.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.SNAPSHOT_ID
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Computes one product's score from the run's snapshot inputs, one query per product. */
@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
class ScoreProcessor(
    @param:Value("#{jobParameters['$SNAPSHOT_ID']}") private val snapshotId: String,
    private val scoreStore: WeeklyRankingScoreStore,
) : ItemProcessor<Long, ProductScore> {
    override fun process(productId: Long): ProductScore {
        return ProductScore(productId = productId, score = scoreStore.sumQuantity(snapshotId, productId))
    }
}
