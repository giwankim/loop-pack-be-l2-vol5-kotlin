package com.loopers.batch.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig
import com.loopers.batch.support.time.JdbcUtcTimes
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime

/**
 * The `publish` step's SQL: the counts it checks, the weekly ranking it makes from the score table, and the publication it
 * writes. Each call joins the caller's transaction, the step's one.
 */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
class WeeklyRankingPublishStore(
    private val jdbcClient: JdbcClient,
) {
    /** How many scores [snapshotId] has. */
    fun countScores(snapshotId: String): Long {
        return jdbcClient
            .sql("SELECT COUNT(*) FROM weekly_ranking_score WHERE snapshot_id = ?")
            .param(snapshotId)
            .query(Long::class.java)
            .single()
    }

    /** How many items [snapshotId]'s weekly ranking has: as many as its scores once it is ranked, none before. */
    fun countRankingItems(snapshotId: String): Long {
        return jdbcClient
            .sql("SELECT COUNT(*) FROM weekly_ranking_item WHERE snapshot_id = ?")
            .param(snapshotId)
            .query(Long::class.java)
            .single()
    }

    /** How many distinct products [snapshotId]'s inputs hold: the number of scores a complete `score` step leaves. */
    fun countInputProducts(snapshotId: String): Long {
        return jdbcClient
            .sql("SELECT COUNT(DISTINCT product_id) FROM weekly_ranking_snapshot_input WHERE snapshot_id = ?")
            .param(snapshotId)
            .query(Long::class.java)
            .single()
    }

    /**
     * Makes [snapshotId]'s weekly ranking from its scores in one statement, so the scores are never read into memory. The
     * rank is the row number by score, highest first, then by product ID, smallest first: it starts at 1, rises by one and
     * is never shared (`CONTEXT.md` 순위).
     */
    fun rankScores(snapshotId: String) {
        jdbcClient
            .sql(
                """
                INSERT INTO weekly_ranking_item (snapshot_id, product_id, score, product_rank)
                SELECT snapshot_id, product_id, score, ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC)
                FROM weekly_ranking_score
                WHERE snapshot_id = ?
                """.trimIndent(),
            )
            .param(snapshotId)
            .update()
    }

    /**
     * Makes [key]'s snapshot the publication of its period, unless the period's publication holds the same or a later
     * revision (`CONTEXT.md` 게시하다). It is one statement on the period's row, with no lock taken first, so two publishes of
     * one period end on the later revision in either order.
     *
     * MySQL assigns the columns left to right, each `IF` reading the values assigned before it, so `revision` comes last:
     * moved first, it would make the other two compare against the incoming revision.
     *
     * It returns the period's publication as read right after the write, since the statement's update count cannot tell
     * the outcome: Connector/J reports found rows, so an inserted row and a row left as it was both count 1. The read locks the
     * row in share mode, so it reads the latest row rather than the transaction's snapshot, which a publish of the same period
     * committed in between would leave stale. It waits on nothing: the write already holds the row's exclusive lock.
     */
    fun publish(key: SnapshotKey, publishedAt: Instant): Publication {
        jdbcClient
            .sql(
                """
                INSERT INTO weekly_ranking_publication (period_start, snapshot_id, revision, published_at)
                VALUES (?, ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE
                    snapshot_id = IF(incoming.revision > weekly_ranking_publication.revision,
                                     incoming.snapshot_id, weekly_ranking_publication.snapshot_id),
                    published_at = IF(incoming.revision > weekly_ranking_publication.revision,
                                      incoming.published_at, weekly_ranking_publication.published_at),
                    revision = GREATEST(incoming.revision, weekly_ranking_publication.revision)
                """.trimIndent(),
            )
            .params(key.periodStart, key.snapshotId, key.revision, JdbcUtcTimes.toJdbc(publishedAt))
            .update()

        return jdbcClient
            .sql(
                "SELECT snapshot_id, revision, published_at FROM weekly_ranking_publication WHERE period_start = ? FOR SHARE",
            )
            .param(key.periodStart)
            .query { resultSet, _ ->
                Publication(
                    snapshotId = resultSet.getString("snapshot_id"),
                    revision = resultSet.getLong("revision"),
                    publishedAt = JdbcUtcTimes.fromJdbc(resultSet.getObject("published_at", LocalDateTime::class.java)),
                )
            }
            .single()
    }
}
