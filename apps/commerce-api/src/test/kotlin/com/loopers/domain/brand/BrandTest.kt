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
        val name = "가".repeat(100)

        val brand = Brand("  $name\t")

        assertThat(brand.name).isEqualTo(name)
    }

    @Test
    fun `update replaces the name with the new one without surrounding whitespace`() {
        val brand = Brand("루퍼스")

        brand.update("  무신사\t")

        assertThat(brand.name).isEqualTo("무신사")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `update to a blank name throws InvalidNameException and keeps the old name`(name: String) {
        val brand = Brand("루퍼스")

        assertThrows<InvalidNameException> { brand.update(name) }

        assertThat(brand.name).isEqualTo("루퍼스")
    }

    @Test
    fun `update to a name of 101 chars after trimming throws InvalidNameException and keeps the old name`() {
        val brand = Brand("루퍼스")

        assertThrows<InvalidNameException> { brand.update(" " + "가".repeat(101) + " ") }

        assertThat(brand.name).isEqualTo("루퍼스")
    }

    @Test
    fun `normalizeName trims the name the entity would store`() {
        assertThat(Brand.normalizeName("  루퍼스\t")).isEqualTo("루퍼스")
    }

    @Test
    fun `normalizeName leaves an already normalized name alone`() {
        val once = Brand.normalizeName("  루퍼스\t")

        assertThat(Brand.normalizeName(once)).isEqualTo(once)
    }

    @Test
    fun `normalizeName rejects a name the entity would reject`() {
        assertThrows<InvalidNameException> { Brand.normalizeName("   ") }
    }
}
