package com.loopers.support.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoreExceptionTest {
    @Test
    fun `uses the error type message when no detail is given`() {
        val errorTypes = ErrorType.entries

        errorTypes.forEach { errorType ->
            val exception = CoreException(errorType)

            assertThat(exception.message).isEqualTo(errorType.message)
        }
    }

    @Test
    fun `prefixes the error type message with the detail in brackets when one is given`() {
        val exception = CoreException(ErrorType.BRAND_NOT_FOUND, "id = 999")

        assertThat(exception.message).isEqualTo("[id = 999] ${ErrorType.BRAND_NOT_FOUND.message}")
    }
}
