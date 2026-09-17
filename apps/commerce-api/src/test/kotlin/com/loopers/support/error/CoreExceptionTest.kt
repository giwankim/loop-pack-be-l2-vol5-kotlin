package com.loopers.support.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoreExceptionTest {
    @Test
    fun `uses the error type message when no custom message is given`() {
        val errorTypes = ErrorType.entries

        errorTypes.forEach { errorType ->
            val exception = CoreException(errorType)

            assertThat(exception.message).isEqualTo(errorType.message)
        }
    }

    @Test
    fun `uses the custom message when one is given`() {
        val customMessage = "custom message"

        val exception = CoreException(ErrorType.INTERNAL_ERROR, customMessage)

        assertThat(exception.message).isEqualTo(customMessage)
    }
}
