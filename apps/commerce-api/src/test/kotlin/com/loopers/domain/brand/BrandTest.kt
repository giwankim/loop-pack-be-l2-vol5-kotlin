package com.loopers.domain.brand

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BrandTest {
    @DisplayName("브랜드를 만들 때, ")
    @Nested
    inner class Create {
        @DisplayName("이름이 비었거나 공백뿐이면, INVALID_NAME 예외가 발생한다.")
        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "\t\n"])
        fun throwsInvalidName_whenNameIsBlank(name: String) {
            // act
            val result = assertThrows<CoreException> { Brand(name = name) }

            // assert
            assertThat(result.errorType).isEqualTo(ErrorType.INVALID_NAME)
        }

        @DisplayName("앞뒤 공백을 뗀 이름이 101자이면, INVALID_NAME 예외가 발생한다.")
        @Test
        fun throwsInvalidName_whenTrimmedNameExceeds100Chars() {
            // arrange
            val name = " " + "가".repeat(101) + " "

            // act
            val result = assertThrows<CoreException> { Brand(name = name) }

            // assert
            assertThat(result.errorType).isEqualTo(ErrorType.INVALID_NAME)
        }

        @DisplayName("앞뒤 공백을 뗀 이름이 100자이면, 뗀 이름으로 만들어진다.")
        @Test
        fun createsWithTrimmedName_whenTrimmedNameIs100Chars() {
            // arrange
            val name = "가".repeat(100)

            // act
            val brand = Brand(name = "  $name\t")

            // assert
            assertThat(brand.name).isEqualTo(name)
        }
    }
}
