package com.loopers.domain.shared

import com.loopers.domain.InvalidNameException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NameTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `blank name throws InvalidNameException`(value: String) {
        assertThrows<InvalidNameException> { Name(value) }
    }

    @Test
    fun `name of 101 chars after trimming throws InvalidNameException`() {
        val value = " " + "가".repeat(101) + " "

        assertThrows<InvalidNameException> { Name(value) }
    }

    @Test
    fun `name of 100 chars after trimming keeps the trimmed value`() {
        val value = "가".repeat(100)

        val name = Name("  $value\t")

        assertThat(name.value).isEqualTo(value)
    }

    @Test
    fun `names are equal when their trimmed values are equal`() {
        assertThat(Name(" 루퍼스 ")).isEqualTo(Name("루퍼스"))
    }
}
