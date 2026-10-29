package com.loopers.domain.purchaseactivity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ProcessedOrderConfirmationTest {
    /** The time is compared with the incoming event's when it comes again (ADR 0033 rule 3), so what is held is what is stored. */
    @Test
    fun `the confirmation time is cut to the microseconds the column keeps`() {
        val confirmation = createProcessedOrderConfirmation(confirmedAt = Instant.parse("2026-10-27T01:02:03.123456789Z"))

        assertThat(confirmation.confirmedAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
    }
}
