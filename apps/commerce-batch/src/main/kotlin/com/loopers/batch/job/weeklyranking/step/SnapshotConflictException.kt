package com.loopers.batch.job.weeklyranking.step

import java.time.LocalDate

/**
 * A freeze refused because a snapshot already holds its snapshot ID, or its ranking period and revision (`CONTEXT.md` 충돌).
 * The message names both sides, and Batch writes it into the step's exit description, so an operator can tell a conflict
 * from any other failed freeze.
 */
class SnapshotConflictException(
    snapshotId: String,
    periodStart: LocalDate,
    revision: Long,
    conflicting: List<SnapshotKey>,
) : RuntimeException(
    conflicting.joinToString(separator = "; ") { existing ->
        "Freeze of snapshot $snapshotId (period $periodStart, revision $revision) conflicts with snapshot " +
            "${existing.snapshotId} (period ${existing.periodStart}, revision ${existing.revision})"
    },
)

/** The three values that name a snapshot: its ID, and its ranking period and revision, each unique. */
data class SnapshotKey(val snapshotId: String, val periodStart: LocalDate, val revision: Long)
