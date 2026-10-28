package com.loopers.domain.purchaseactivity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class PurchaseActivityTest {
    @ParameterizedTest
    @ValueSource(ints = [0, -1, Int.MIN_VALUE])
    fun `a non-positive quantity is refused`(quantity: Int) {
        val exception = assertThrows<IllegalArgumentException> { createPurchaseActivity(quantity = quantity) }

        assertThat(exception.message).isEqualTo("A purchase activity's quantity must be positive, but was $quantity.")
    }

    /** MySQL rounds a seventh fractional digit rather than dropping it, so an uncut time would read back a microsecond later. */
    @Test
    fun `the confirmation time is cut to the microseconds the column keeps`() {
        val activity = createPurchaseActivity(confirmedAt = Instant.parse("2026-10-27T01:02:03.123456789Z"))

        assertThat(activity.confirmedAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
    }
}
