package com.loopers.domain.brand

import com.loopers.domain.shared.InvalidNameException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BrandTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `blank name throws InvalidNameException`(value: String) {
        assertThrows<InvalidNameException> { Brand(value) }
    }

    @Test
    fun `name is stored without surrounding whitespace`() {
        val brand = Brand("  루퍼스\t")

        assertThat(brand.name).isEqualTo("루퍼스")
    }

    @Test
    fun `name of 101 chars after trimming throws InvalidNameException`() {
        assertThrows<InvalidNameException> { Brand(" " + "가".repeat(101) + " ") }
    }

    @Test
    fun `name of 100 chars after trimming is kept`() {
        val value = "가".repeat(100)

        val brand = Brand("  $value\t")

        assertThat(brand.name).isEqualTo(value)
    }
}
