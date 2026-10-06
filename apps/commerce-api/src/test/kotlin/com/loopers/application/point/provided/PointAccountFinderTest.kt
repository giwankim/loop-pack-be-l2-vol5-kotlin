package com.loopers.application.point.provided

import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.user.UserFixture
import com.loopers.support.countPointAccounts
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.stereotype.ApplicationServiceTest
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [PointAccountFinder]를 실제 MySQL 위에서 확인한다. 잔액은 같은 조각의 [PointCharger]로 채운다.
 * 정리와 flush/clear, 계정을 테이블에서 SQL로 세는 까닭은 [PointChargerTest]와 같다.
 */
@ApplicationServiceTest
class PointAccountFinderTest(
    private val pointAccountFinder: PointAccountFinder,
    private val pointCharger: PointCharger,
    private val userFixture: UserFixture,
    private val entityManager: EntityManager,
) {
    @Test
    fun `findBalance is zero for a fresh account and the current balance after charges`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()
        val fresh = pointAccountFinder.findBalance(user.id)

        pointCharger.charge(user.id, createPointChargeRequest(amount = 10_000))
        pointCharger.charge(user.id, createPointChargeRequest(amount = 500))
        entityManager.flushAndClear()

        assertThat(fresh.balance).isZero()
        assertThat(pointAccountFinder.findBalance(user.id).balance).isEqualTo(10_500L)
    }

    @Test
    fun `finding the balance of an unknown user throws UNAUTHORIZED and creates no account`() {
        val exception = assertThrows<CoreException> { pointAccountFinder.findBalance(999L) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED)
        assertThat(entityManager.countPointAccounts(999L)).isZero()
    }

    /** 사용자는 있는데 계정이 없는 것은 fixture와 데이터의 불일치다. 0원 계정을 만들어 주지 않고 내부 오류다(설계 5.9, 6 끝). */
    @Test
    fun `reading the balance of an existing user without an account is an internal error and creates no account`() {
        val user = userFixture.registerUserWithoutAccount()
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> { pointAccountFinder.findBalance(user.id) }
        entityManager.flushAndClear()

        assertThat(exception.errorType).isEqualTo(ErrorType.POINT_ACCOUNT_MISSING)
        assertThat(entityManager.countPointAccounts(user.id)).isZero()
    }
}
