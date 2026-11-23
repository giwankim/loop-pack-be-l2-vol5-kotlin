package com.loopers.batch.job.weeklyranking.step

import java.time.LocalDate

/** The values that name a snapshot: its ID, and its ranking period and revision. Each of the two is unique on its own. */
data class SnapshotKey(val snapshotId: String, val periodStart: LocalDate, val revision: Long) {
    fun describe(): String {
        return "snapshot $snapshotId (period $periodStart, revision $revision)"
    }
}
