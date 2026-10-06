package com.loopers.application.point.provided

import com.loopers.application.point.required.PointAccountRepository
import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.shared.InvalidMoneyException
import com.loopers.domain.user.UserFixture
import com.loopers.support.balanceOf
import com.loopers.support.countPointAccounts
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.stereotype.ApplicationServiceTest
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [PointCharger]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.brand.provided.BrandRegisterTest]와 같다.
 *
 * 잔액은 저장 약속을 거치지 않고 테이블을 SQL로 읽는다. "잔액이 그대로다"는 테이블의 사실이다.
 * 커밋과 롤백 자체는 테스트 트랜잭션에 가려지므로 [PointChargerTransactionTest]가 따로 본다.
 */
@ApplicationServiceTest
class PointChargerTest(
    private val pointCharger: PointCharger,
    private val pointAccountFinder: PointAccountFinder,
    private val pointAccountRepository: PointAccountRepository,
    private val userFixture: UserFixture,
    private val entityManager: EntityManager,
) {
    @Test
    fun `charging adds to the balance`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()

        val info = pointCharger.charge(user.id, createPointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()
        val accountId = accountIdOf(user.id)

        assertThat(info.balance).isEqualTo(10_000L)
        assertThat(entityManager.balanceOf(accountId)).isEqualTo(10_000L)
    }

    /** 요청마다 새 충전이다. 같은 충전액을 두 번 보내면 두 번 늘어난다(ADR 0005). */
    @Test
    fun `charging the same amount twice adds it twice`() {
        val user = userFixture.registerUser()
        pointCharger.charge(user.id, createPointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()

        val info = pointCharger.charge(user.id, createPointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()
        val accountId = accountIdOf(user.id)

        assertThat(info.balance).isEqualTo(20_000L)
        assertThat(entityManager.balanceOf(accountId)).isEqualTo(20_000L)
    }

    /** 상품 가격의 상한은 잔액의 상한이 아니다(설계 5.7). */
    @Test
    fun `the balance may exceed the product price cap`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()

        val info = pointCharger.charge(user.id, createPointChargeRequest(amount = 1_000_000_001))
        entityManager.flushAndClear()

        assertThat(info.balance).isEqualTo(1_000_000_001L)
        assertThat(pointAccountFinder.findBalance(user.id).balance).isEqualTo(1_000_000_001L)
    }

    @Test
    fun `charging zero or a negative amount is rejected by request validation and changes nothing`() {
        val user = userFixture.registerUser()
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
        assertThat(entityManager.balanceOf(accountIdOf(user.id))).isZero()
    }

    @Test
    fun `a charge that overflows the balance is rejected and changes nothing`() {
        val user = userFixture.registerUser()
        pointCharger.charge(user.id, createPointChargeRequest(amount = Long.MAX_VALUE))
        entityManager.flushAndClear()

        assertThrows<InvalidMoneyException> {
            pointCharger.charge(user.id, createPointChargeRequest(amount = 1))
        }
        entityManager.flushAndClear()

        assertThat(entityManager.balanceOf(accountIdOf(user.id))).isEqualTo(Long.MAX_VALUE)
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
        val user = userFixture.registerUserWithoutAccount()
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> {
            pointCharger.charge(user.id, createPointChargeRequest())
        }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.POINT_ACCOUNT_MISSING)
        assertThat(entityManager.countPointAccounts(user.id)).isZero()
    }

    private fun accountIdOf(userId: Long): Long = pointAccountRepository.findByUserId(userId)!!.id
}
