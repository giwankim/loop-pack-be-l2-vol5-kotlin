package com.loopers.support

import com.loopers.batch.job.weeklyranking.step.SnapshotInput
import java.time.Instant

/**
 * The assignment's section 6 inputs and the checksums the weekly ranking's tests expect. The checksums were worked out apart
 * from the code, with `printf … | shasum -a 256` over the canonical text.
 */
object WeeklyRankingInputs {
    /** Orders 42 (product 7 × 2, product 8 × 1), 43 (7 × 3) and 44 (9 × 5) in `order_id`, `product_id` order, inside 2026-08-17's week. */
    val ASSIGNMENT_INPUTS: List<SnapshotInput> = listOf(
        SnapshotInput(orderId = 42, productId = 7, quantity = 2, confirmedAt = Instant.parse("2026-08-17T00:00:00Z")),
        SnapshotInput(orderId = 42, productId = 8, quantity = 1, confirmedAt = Instant.parse("2026-08-17T00:00:00Z")),
        SnapshotInput(orderId = 43, productId = 7, quantity = 3, confirmedAt = Instant.parse("2026-08-19T13:45:30.123456Z")),
        SnapshotInput(orderId = 44, productId = 9, quantity = 5, confirmedAt = Instant.parse("2026-08-23T23:59:59.999999Z")),
    )

    const val ASSIGNMENT_CHECKSUM = "d07eff39ac33dc7bb7f63110fcf1c2a3f1c52fec496f369dada87d83a5c4720b"

    /**
     * The result checksum of the assignment's expected ranking: the canonical text `7\t5\t1\n9\t5\t2\n8\t1\t3\n`, one line
     * per product in rank order with its product ID, score and rank.
     */
    const val ASSIGNMENT_RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2"

    /** The SHA-256 of the empty string. */
    const val EMPTY_CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
