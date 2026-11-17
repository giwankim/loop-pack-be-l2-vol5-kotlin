package com.loopers.batch.job.weeklyranking.step

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat

/**
 * The count and checksum of a snapshot's inputs, fed one input at a time so a week of inputs is never held in memory
 * (`CONTEXT.md` 체크섬). Inputs must arrive in `order_id`, `product_id` order.
 *
 * The checksum is the lowercase hex SHA-256 of the assignment's canonical text: one line per input, its order ID, product ID,
 * quantity and confirmation time joined by TAB, every line ended by LF, in UTF-8. The time is UTC with six fraction digits
 * always, so one instant has one spelling. No input gives the SHA-256 of the empty string.
 */
class SnapshotInputDigest {
    private val sha256: MessageDigest = MessageDigest.getInstance("SHA-256")

    var count: Long = 0
        private set

    fun add(input: SnapshotInput) {
        sha256.update(canonicalLine(input).toByteArray(Charsets.UTF_8))
        count++
    }

    /** The checksum of the inputs added so far. It ends the digest: nothing is added after it. */
    fun checksum(): String {
        return HexFormat.of().formatHex(sha256.digest())
    }

    companion object {
        private val CONFIRMED_AT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

        /** The input's line of the canonical text, LF included. */
        fun canonicalLine(input: SnapshotInput): String {
            return "${input.orderId}\t${input.productId}\t${input.quantity}\t${format(input.confirmedAt)}\n"
        }

        private fun format(confirmedAt: Instant): String {
            return CONFIRMED_AT_FORMAT.format(confirmedAt)
        }
    }
}
