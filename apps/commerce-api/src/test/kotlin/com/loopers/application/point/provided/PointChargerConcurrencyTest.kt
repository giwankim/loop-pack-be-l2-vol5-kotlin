package com.loopers.application.point.provided

import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.shared.Money
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 같은 포인트 계정을 두고 겹치는 충전을 실제 MySQL의 행 잠금으로 확인한다(ADR 0019 규칙 1). 충전들을 start gate로 함께 풀고,
 * 결과를 성공·업무 거절·기술 오류로 센 뒤 새 트랜잭션에서 잔액을 읽는다.
 *
 * 확정이 포인트 계정을 떠났으므로(ADR 0027) 계정을 바꾸는 경로는 충전뿐이다. 그래서 계정 잠금은 충전끼리의 경쟁으로 시험한다.
 */
class PointChargerConcurrencyTest(
    private val pointCharger: PointCharger,
    private val pointAccountFinder: PointAccountFinder,
) : BaseCommittingApplicationServiceTest() {
    /**
     * 충전들은 계정 행의 잠금에서 차례로 지나가므로, 잔액의 식은 처음 잔액 + 성공한 충전 = 최종 잔액이고 뒤진 충전은 앞선 충전이 커밋한 잔액에 더한다.
     * 계정을 잠그지 않으면 두 충전이 같은 잔액을 읽고 저마다 그 잔액에 더한 절댓값을 써서, 나중에 커밋한 쪽이 앞의 충전을 덮는다.
     * 충전액이 서로 달라 어느 충전이 사라졌는지도 잔액에 드러난다.
     */
    @Test
    fun `two charges released together on one account both succeed and both reach the balance`() {
        val initialBalance = 10_000L
        prepareUser()
        charge(amount = initialBalance)
        val chargeAmounts = listOf(2_000L, 3_000L)

        val results = runConcurrently(
            chargeAmounts.map { amount -> { pointCharger.charge(user.id, createPointChargeRequest(amount = amount)) } },
        )

        val outcomes = tally(results)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 2, rejections = 0, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(chargeAmounts.size)
        assertThat(results.map { it.getOrThrow().balance }).contains(Money(15_000))
        inNewTransaction {
            val charged = results.zip(chargeAmounts).filter { (result, _) -> result.isSuccess }.sumOf { (_, amount) -> amount }
            val finalBalance = pointAccountFinder.findByUser(user.id).balance
            assertThat(finalBalance).isEqualTo(Money(initialBalance + charged))
            assertThat(finalBalance).isEqualTo(Money(15_000))
        }
    }
}
