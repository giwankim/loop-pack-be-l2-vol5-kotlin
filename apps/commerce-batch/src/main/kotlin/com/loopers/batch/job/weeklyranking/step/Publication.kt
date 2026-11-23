package com.loopers.batch.job.weeklyranking.step

import java.time.Instant

/** A ranking period's publication: the snapshot the period shows, its revision, and when it was published (`CONTEXT.md` 게시). */
data class Publication(val snapshotId: String, val revision: Long, val publishedAt: Instant) {
    /**
     * Whether this is the publication that publishing [key] at [publishedAt] wrote, rather than one it left in place. The
     * published-at time tells a republished snapshot from a moved one; a stopped clock giving the same snapshot the same time
     * twice would read as moved.
     */
    fun isWrittenBy(key: SnapshotKey, publishedAt: Instant): Boolean {
        return snapshotId == key.snapshotId && this.publishedAt == publishedAt
    }

    fun describe(): String {
        return "snapshot $snapshotId (revision $revision)"
    }
}
