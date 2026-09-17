package com.loopers.domain.brand

import com.loopers.domain.shared.InvalidNameException
import com.loopers.domain.shared.Name
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BrandTest {
    @Test
    fun `name of 101 chars after trimming throws InvalidNameException`() {
        assertThrows<InvalidNameException> { Brand(Name(" " + "가".repeat(101) + " ")) }
    }

    @Test
    fun `name of 100 chars after trimming is kept`() {
        val value = "가".repeat(100)

        val brand = Brand(Name("  $value\t"))

        assertThat(brand.name).isEqualTo(Name(value))
    }
}
