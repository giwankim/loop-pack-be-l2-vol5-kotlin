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
    fun `blank name throws InvalidNameException`(name: String) {
        assertThrows<InvalidNameException> { Brand(name) }
    }

    @Test
    fun `name is stored as sent, surrounding whitespace included`() {
        val brand = Brand("  루퍼스\t")

        assertThat(brand.name).isEqualTo("  루퍼스\t")
    }

    @Test
    fun `name of 101 chars counting surrounding spaces throws InvalidNameException`() {
        assertThrows<InvalidNameException> { Brand(" " + "가".repeat(99) + " ") }
    }

    @Test
    fun `name of 100 chars counting surrounding spaces is kept as sent`() {
        val name = " " + "가".repeat(98) + " "

        val brand = Brand(name)

        assertThat(brand.name).isEqualTo(name)
    }

    @Test
    fun `update replaces the name with the new one as sent`() {
        val brand = createBrand()

        brand.update("  무신사\t")

        assertThat(brand.name).isEqualTo("  무신사\t")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `update to a blank name throws InvalidNameException and keeps the old name`(name: String) {
        val brand = createBrand(name = "루퍼스")

        assertThrows<InvalidNameException> { brand.update(name) }

        assertThat(brand.name).isEqualTo("루퍼스")
    }

    @Test
    fun `update to a name of 101 chars counting surrounding spaces throws InvalidNameException and keeps the old name`() {
        val brand = createBrand(name = "루퍼스")

        assertThrows<InvalidNameException> { brand.update(" " + "가".repeat(99) + " ") }

        assertThat(brand.name).isEqualTo("루퍼스")
    }
}
