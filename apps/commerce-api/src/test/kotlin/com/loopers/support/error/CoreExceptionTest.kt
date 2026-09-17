package com.loopers.support.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoreExceptionTest {
    @Test
    fun `uses the error type message`() {
        val errorTypes = ErrorType.entries

        errorTypes.forEach { errorType ->
            val exception = CoreException(errorType)

            assertThat(exception.message).isEqualTo(errorType.message)
        }
    }
}
