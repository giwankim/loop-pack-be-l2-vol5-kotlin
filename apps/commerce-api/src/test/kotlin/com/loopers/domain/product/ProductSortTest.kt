package com.loopers.domain.product

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class ProductSortTest {
    @Test
    fun `from reads the API value of every sort`() {
        assertAll(
            { assertThat(ProductSort.from("latest")).isEqualTo(ProductSort.LATEST) },
            { assertThat(ProductSort.from("price_asc")).isEqualTo(ProductSort.PRICE_ASC) },
            { assertThat(ProductSort.from("likes_desc")).isEqualTo(ProductSort.LIKES_DESC) },
        )
    }

    @Test
    fun `every sort answers to exactly one API value`() {
        val apiValues = ProductSort.entries.map { it.apiValue }

        assertAll(
            { assertThat(apiValues).doesNotHaveDuplicates() },
            { assertThat(apiValues).allSatisfy { assertThat(ProductSort.from(it)).isNotNull() } },
        )
    }

    @Test
    fun `from returns null for a word no sort answers to`() {
        assertThat(ProductSort.from("newest")).isNull()
    }

    /** 빈 문자열은 "고르지 않음"이 아니다. 기준을 고르지 않은 요청은 `sort`를 아예 싣지 않아 기본값이 쓰인다. */
    @Test
    fun `from returns null rather than falling back for an empty value`() {
        assertThat(ProductSort.from("")).isNull()
    }

    /** 상수 이름은 API 철자가 아니다. 밖에서 오는 값은 [ProductSort.apiValue]와만 맞춰 본다. */
    @Test
    fun `from does not read the constant name`() {
        assertAll(
            { assertThat(ProductSort.from("PRICE_ASC")).isNull() },
            { assertThat(ProductSort.from("Latest")).isNull() },
        )
    }
}
