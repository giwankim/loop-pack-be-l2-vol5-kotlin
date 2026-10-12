package com.loopers.domain.point

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `PointFixtures.kt`가 약속하는 기본값의 범위를 확인한다. 기본값이 범위를 벗어나면 그것을 쓰는 테스트가 가끔만 깨지므로,
 * 여기서 기본값을 많이 뽑아 결정적으로 드러낸다(ADR 0010).
 */
class PointFixturesTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    private val validator = Validation.buildDefaultValidatorFactory().validator

    /** `@Min(1)`에는 상한이 없다. 1,000,000원 이하라 기본 충전을 여러 번 더해도 잔액이 넘치지 않는다. */
    @Test
    fun `createPointChargeRequest satisfies the request's own constraints and charges at most 1_000_000`() {
        val requests = List(SAMPLES) { createPointChargeRequest() }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request -> assertThat(request.amount).isBetween(1, 1_000_000) }
    }

    @Test
    fun `createPointChargeRequest uses the amount it is given`() {
        val request = createPointChargeRequest(amount = 1_000_000_001)

        assertThat(request.amount).isEqualTo(1_000_000_001)
    }
}
