package com.loopers.job.weeklyranking.step

import com.loopers.batch.job.weeklyranking.step.SnapshotInput
import com.loopers.batch.job.weeklyranking.step.SnapshotInputDigest
import com.loopers.support.WeeklyRankingInputs.ASSIGNMENT_CHECKSUM
import com.loopers.support.WeeklyRankingInputs.ASSIGNMENT_INPUTS
import com.loopers.support.WeeklyRankingInputs.EMPTY_CHECKSUM
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The checksum of spec 237 without a database: the canonical text of the assignment's section 6 and its SHA-256. The
 * expected values are literals, worked out apart from this code with `printf … | shasum -a 256`.
 */
class SnapshotInputDigestTest {
    @Test
    fun `the assignment's four inputs give the expected canonical text and checksum`() {
        // arrange
        val inputs = ASSIGNMENT_INPUTS
        val digest = SnapshotInputDigest()

        // act
        inputs.forEach { digest.add(it) }

        // assert
        assertThat(inputs.joinToString("") { SnapshotInputDigest.canonicalLine(it) })
            .isEqualTo(
                "42\t7\t2\t2026-08-17T00:00:00.000000Z\n" +
                    "42\t8\t1\t2026-08-17T00:00:00.000000Z\n" +
                    "43\t7\t3\t2026-08-19T13:45:30.123456Z\n" +
                    "44\t9\t5\t2026-08-23T23:59:59.999999Z\n",
            )
        assertThat(digest.count).isEqualTo(4)
        assertThat(digest.checksum()).isEqualTo(ASSIGNMENT_CHECKSUM)
    }

    @Test
    fun `empty input gives the SHA-256 of the empty string`() {
        // arrange
        val digest = SnapshotInputDigest()

        // act
        val checksum = digest.checksum()

        // assert
        assertThat(digest.count).isZero()
        assertThat(checksum).isEqualTo(EMPTY_CHECKSUM)
    }

    @Test
    fun `a time with no fraction and a time with microseconds are both written with six fraction digits and Z`() {
        // arrange
        val noFraction = SnapshotInput(
            orderId = 1,
            productId = 2,
            quantity = 3,
            confirmedAt = Instant.parse("2026-08-17T00:00:00Z"),
        )
        val micros = noFraction.copy(confirmedAt = Instant.parse("2026-08-17T00:00:00.000001Z"))

        // act
        val noFractionLine = SnapshotInputDigest.canonicalLine(noFraction)
        val microsLine = SnapshotInputDigest.canonicalLine(micros)

        // assert
        assertThat(noFractionLine).isEqualTo("1\t2\t3\t2026-08-17T00:00:00.000000Z\n")
        assertThat(microsLine).isEqualTo("1\t2\t3\t2026-08-17T00:00:00.000001Z\n")
    }
}
