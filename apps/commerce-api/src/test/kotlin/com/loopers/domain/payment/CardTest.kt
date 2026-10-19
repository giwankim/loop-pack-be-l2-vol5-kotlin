package com.loopers.domain.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CardTest {
    @Test
    fun `the string form shows the card type and masks the number`() {
        val card = Card(type = "TEST", number = "1234-5678-9012-3456")

        assertThat(card.toString())
            .contains("TEST")
            .doesNotContain("1234", "5678", "9012", "3456")
    }
}
