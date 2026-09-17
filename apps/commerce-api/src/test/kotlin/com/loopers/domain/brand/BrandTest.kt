package com.loopers.domain.brand

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BrandTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `creating with a blank name throws INVALID_NAME`(name: String) {
        val result = assertThrows<CoreException> { Brand(name = name) }

        assertThat(result.errorType).isEqualTo(ErrorType.INVALID_NAME)
    }

    @Test
    fun `creating with a name of 101 chars after trimming throws INVALID_NAME`() {
        val name = " " + "가".repeat(101) + " "

        val result = assertThrows<CoreException> { Brand(name = name) }

        assertThat(result.errorType).isEqualTo(ErrorType.INVALID_NAME)
    }

    @Test
    fun `creating with a name of 100 chars after trimming keeps the trimmed name`() {
        val name = "가".repeat(100)

        val brand = Brand(name = "  $name\t")

        assertThat(brand.name).isEqualTo(name)
    }
}
