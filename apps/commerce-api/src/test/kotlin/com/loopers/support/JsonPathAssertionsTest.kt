package com.loopers.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.json.JsonContent

class JsonPathAssertionsTest {
    @Test
    fun `isEqualToLong passes for an equal value within the Int range`() {
        assertThat(JsonContent("""{"id": 1}""")).extractingPath("$.id").isEqualToLong(1L)
    }

    @Test
    fun `isEqualToLong passes for an equal value beyond the Int range`() {
        assertThat(JsonContent("""{"id": 3000000000}""")).extractingPath("$.id").isEqualToLong(3_000_000_000L)
    }

    @Test
    fun `isEqualToLong fails for a different value`() {
        assertThrows<AssertionError> {
            assertThat(JsonContent("""{"id": 1}""")).extractingPath("$.id").isEqualToLong(2L)
        }
    }

    @Test
    fun `isEqualToLong fails for a decimal with the same whole value`() {
        assertThrows<AssertionError> {
            assertThat(JsonContent("""{"id": 1.0}""")).extractingPath("$.id").isEqualToLong(1L)
        }
    }
}
