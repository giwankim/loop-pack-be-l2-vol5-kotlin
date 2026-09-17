package com.loopers.domain.brand

import com.loopers.domain.InvalidNameException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BrandTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `creating with a blank name throws InvalidNameException`(name: String) {
        assertThrows<InvalidNameException> { Brand(name = name) }
    }

    @Test
    fun `creating with a name of 101 chars after trimming throws InvalidNameException`() {
        val name = " " + "가".repeat(101) + " "

        assertThrows<InvalidNameException> { Brand(name = name) }
    }

    @Test
    fun `creating with a name of 100 chars after trimming keeps the trimmed name`() {
        val name = "가".repeat(100)

        val brand = Brand(name = "  $name\t")

        assertThat(brand.name).isEqualTo(name)
    }
}
