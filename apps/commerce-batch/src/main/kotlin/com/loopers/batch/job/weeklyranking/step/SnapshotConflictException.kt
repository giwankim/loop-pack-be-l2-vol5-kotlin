package com.loopers.batch.job.weeklyranking.step

/**
 * A freeze refused because a snapshot already holds its snapshot ID, or its ranking period and revision (`CONTEXT.md` 충돌).
 * The message names both sides, and Batch writes it into the step's exit description, so an operator can tell a conflict
 * from any other failed freeze.
 */
class SnapshotConflictException(
    refused: SnapshotKey,
    existing: List<SnapshotKey>,
) : RuntimeException(
    existing.joinToString(separator = "; ") { snapshot ->
        "Freeze of ${refused.describe()} conflicts with ${snapshot.describe()}"
    },
)
