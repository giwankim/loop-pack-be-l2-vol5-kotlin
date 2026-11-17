package com.loopers.batch.job.weeklyranking.step

import java.time.Instant

/** One purchase activity as a snapshot holds it: one order and one product (`CONTEXT.md` 스냅샷 입력). */
data class SnapshotInput(
    val orderId: Long,
    val productId: Long,
    val quantity: Int,
    val confirmedAt: Instant,
)
