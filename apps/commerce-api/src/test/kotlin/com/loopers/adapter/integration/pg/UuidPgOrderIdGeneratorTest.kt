package com.loopers.adapter.integration.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 운영의 PG 주문 번호 생성기가 PG의 형식 규칙(6자 이상)을 지키고 결제마다 다른 값을 내는지 본다(ADR 0029 규칙 1).
 * 값이 겹치면 두 결제가 같은 PG 주문 번호를 가져, 한 결제의 대사가 다른 결제의 PG 거래를 읽는다.
 */
class UuidPgOrderIdGeneratorTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    private val generator = UuidPgOrderIdGenerator()

    @Test
    fun `every PG order ID is at least 6 ASCII characters and no two are the same`() {
        val pgOrderIds = List(SAMPLES) { generator.generate() }

        assertThat(pgOrderIds).doesNotHaveDuplicates()
        assertThat(pgOrderIds).allSatisfy { pgOrderId ->
            assertThat(pgOrderId.length).isGreaterThanOrEqualTo(6)
            assertThat(pgOrderId).isASCII()
        }
    }
}
