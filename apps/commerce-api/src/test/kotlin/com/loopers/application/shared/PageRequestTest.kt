package com.loopers.application.shared

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PageRequestTest {
    @Test
    fun `a missing page and size fall back to the first page of twenty`() {
        val request = PageRequest.of(page = null, size = null)

        assertAll(
            { assertThat(request.page).isZero() },
            { assertThat(request.size).isEqualTo(20) },
        )
    }

    @Test
    fun `a negative page throws INVALID_PAGE`() {
        val exception = assertThrows<CoreException> { PageRequest(page = -1) }

        assertThat(exception.errorType).isEqualTo(ErrorType.INVALID_PAGE)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 101])
    fun `a size outside one to a hundred throws INVALID_PAGE`(size: Int) {
        val exception = assertThrows<CoreException> { PageRequest(size = size) }

        assertThat(exception.errorType).isEqualTo(ErrorType.INVALID_PAGE)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 100])
    fun `a size at the ends of one to a hundred is accepted`(size: Int) {
        val request = PageRequest(size = size)

        assertThat(request.size).isEqualTo(size)
    }
}
