package com.loopers.batch.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator.Companion.SNAPSHOT_ID
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Stores a chunk's scores under the run's snapshot, in the chunk's transaction. */
@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
class ScoreWriter(
    @param:Value("#{jobParameters['$SNAPSHOT_ID']}") private val snapshotId: String,
    private val scoreStore: WeeklyRankingScoreStore,
) : ItemWriter<ProductScore> {
    override fun write(chunk: Chunk<out ProductScore>) {
        scoreStore.saveScores(snapshotId, chunk.items)
    }
}
