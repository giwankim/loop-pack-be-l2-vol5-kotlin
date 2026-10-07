package com.loopers.application.point.provided

import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.shared.InvalidMoneyException
import com.loopers.support.balanceOf
import com.loopers.support.countPointAccounts
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [PointCharger]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.brand.provided.BrandRegisterTest]와 같다.
 *
 * 잔액은 저장 약속을 거치지 않고 테이블을 SQL로 읽는다. "잔액이 그대로다"는 테이블의 사실이다.
 * 커밋과 롤백 자체는 테스트 트랜잭션에 가려지므로 [com.loopers.adapter.webapi.v1.order.OrderConfirmationApiMockMvcTest]가
 * 본다. 충전한 뒤 별도 요청으로 잔액을 읽고, 늦은 실패의 롤백은 여러 행을 쓰는 확정에서 본다(설계 18.1).
 */
class PointChargerTest(
    private val pointCharger: PointCharger,
    private val pointAccountFinder: PointAccountFinder,
) : BaseApplicationServiceTest() {
    @Test
    fun `charging adds to the balance`() {
        prepareUser()
        entityManager.flushAndClear()

        val info = pointCharger.charge(user.id, createPointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()

        assertThat(info.balance).isEqualTo(10_000L)
        assertThat(entityManager.balanceOf(pointAccount.id)).isEqualTo(10_000L)
    }

    /** 요청마다 새 충전이다. 같은 충전액을 두 번 보내면 두 번 늘어난다(ADR 0005). */
    @Test
    fun `charging the same amount twice adds it twice`() {
        prepareUser()
        charge(amount = 10_000)
        entityManager.flushAndClear()

        val info = pointCharger.charge(user.id, createPointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()

        assertThat(info.balance).isEqualTo(20_000L)
        assertThat(entityManager.balanceOf(pointAccount.id)).isEqualTo(20_000L)
    }

    /** 상품 가격의 상한은 잔액의 상한이 아니다(설계 5.7). */
    @Test
    fun `the balance may exceed the product price cap`() {
        prepareUser()
        entityManager.flushAndClear()

        val info = pointCharger.charge(user.id, createPointChargeRequest(amount = 1_000_000_001))
        entityManager.flushAndClear()

        assertThat(info.balance).isEqualTo(1_000_000_001L)
        assertThat(pointAccountFinder.findBalance(user.id).balance).isEqualTo(1_000_000_001L)
    }

    @Test
    fun `charging zero or a negative amount is rejected by request validation and changes nothing`() {
        prepareUser()
        entityManager.flushAndClear()

        assertThat(
            assertThrows<ConstraintViolationException> {
                pointCharger.charge(user.id, createPointChargeRequest(amount = 0))
            }.constraintViolations.map { it.message },
        ).containsExactly("충전액은 1원 이상이어야 합니다.")
        assertThat(
            assertThrows<ConstraintViolationException> {
                pointCharger.charge(user.id, createPointChargeRequest(amount = -1))
            }.constraintViolations.map { it.message },
        ).containsExactly("충전액은 1원 이상이어야 합니다.")
        entityManager.flushAndClear()
        assertThat(entityManager.balanceOf(pointAccount.id)).isZero()
    }

    @Test
    fun `a charge that overflows the balance is rejected and changes nothing`() {
        prepareUser()
        charge(amount = Long.MAX_VALUE)
        entityManager.flushAndClear()

        assertThrows<InvalidMoneyException> {
            pointCharger.charge(user.id, createPointChargeRequest(amount = 1))
        }
        entityManager.flushAndClear()

        assertThat(entityManager.balanceOf(pointAccount.id)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `charging as an unknown user throws UNAUTHORIZED and creates no account`() {
        val exception = assertThrows<CoreException> {
            pointCharger.charge(999L, createPointChargeRequest())
        }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED)
        assertThat(entityManager.countPointAccounts(999L)).isZero()
    }

    /** 사용자는 있는데 계정이 없는 것은 fixture와 데이터의 불일치다. 0원 계정을 만들어 주지 않고 내부 오류다(설계 5.9, 6 끝). */
    @Test
    fun `charging as an existing user without an account is an internal error and creates no account`() {
        prepareUserWithoutAccount()
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> {
            pointCharger.charge(user.id, createPointChargeRequest())
        }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.POINT_ACCOUNT_MISSING)
        assertThat(entityManager.countPointAccounts(user.id)).isZero()
    }
}
