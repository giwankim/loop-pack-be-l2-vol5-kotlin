package com.loopers.application.point

import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.shared.InvalidMoneyException
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.UserFixture
import com.loopers.utils.balanceOf
import com.loopers.utils.countPointAccounts
import com.loopers.utils.countPointHistories
import com.loopers.utils.flushAndClear
import com.loopers.utils.lastPointHistoryRow
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * [PointService]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.brand.BrandServiceTest]와 같다.
 *
 * 잔액과 이력은 저장 약속을 거치지 않고 테이블을 SQL로 읽는다. "잔액이 그대로다", "이력이 하나다"는 테이블의 사실이다.
 * 커밋과 롤백 자체는 테스트 트랜잭션에 가려지므로 [PointServiceTransactionTest]가 따로 본다.
 */
@SpringBootTest
@Transactional
class PointServiceTest(
    private val pointService: PointService,
    private val pointAccountRepository: PointAccountRepository,
    private val userFixture: UserFixture,
    private val entityManager: EntityManager,
) {
    @Test
    fun `charging adds to the balance and leaves one CHARGE history with the amount and the balance after`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()

        val info = pointService.charge(user.id, PointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()
        val accountId = accountIdOf(user.id)

        assertAll(
            { assertThat(info.balance).isEqualTo(10_000L) },
            { assertThat(entityManager.balanceOf(accountId)).isEqualTo(10_000L) },
            { assertThat(entityManager.countPointHistories(accountId)).isOne() },
            { assertThat(entityManager.lastPointHistoryRow(accountId)).containsExactly("CHARGE", 10_000L, 10_000L) },
        )
    }

    @Test
    fun `a second charge adds to the balance and leaves a second history`() {
        val user = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()

        val info = pointService.charge(user.id, PointChargeRequest(amount = 500))
        entityManager.flushAndClear()
        val accountId = accountIdOf(user.id)

        assertAll(
            { assertThat(info.balance).isEqualTo(10_500L) },
            { assertThat(entityManager.balanceOf(accountId)).isEqualTo(10_500L) },
            { assertThat(entityManager.countPointHistories(accountId)).isEqualTo(2L) },
            { assertThat(entityManager.lastPointHistoryRow(accountId)).containsExactly("CHARGE", 500L, 10_500L) },
        )
    }

    /** 요청마다 새 충전이다. 같은 충전액을 두 번 보내면 두 번 늘어난다(ADR 0005). */
    @Test
    fun `charging the same amount twice adds it twice and leaves two histories`() {
        val user = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()

        val info = pointService.charge(user.id, PointChargeRequest(amount = 10_000))
        entityManager.flushAndClear()
        val accountId = accountIdOf(user.id)

        assertAll(
            { assertThat(info.balance).isEqualTo(20_000L) },
            { assertThat(entityManager.balanceOf(accountId)).isEqualTo(20_000L) },
            { assertThat(entityManager.countPointHistories(accountId)).isEqualTo(2L) },
        )
    }

    @Test
    fun `findBalance is zero for a fresh account and the current balance after charges`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()
        val fresh = pointService.findBalance(user.id)

        pointService.charge(user.id, PointChargeRequest(amount = 10_000))
        pointService.charge(user.id, PointChargeRequest(amount = 500))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(fresh.balance).isZero() },
            { assertThat(pointService.findBalance(user.id).balance).isEqualTo(10_500L) },
        )
    }

    /** 상품 가격의 상한은 잔액의 상한이 아니다(설계 5.7). */
    @Test
    fun `the balance may exceed the product price cap`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()

        val info = pointService.charge(user.id, PointChargeRequest(amount = 1_000_000_001))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(info.balance).isEqualTo(1_000_000_001L) },
            { assertThat(pointService.findBalance(user.id).balance).isEqualTo(1_000_000_001L) },
        )
    }

    @Test
    fun `charging zero or a negative amount is rejected by request validation and changes nothing`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()

        assertAll(
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        pointService.charge(user.id, PointChargeRequest(amount = 0))
                    }.constraintViolations.map { it.message },
                ).containsExactly("충전액은 1원 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        pointService.charge(user.id, PointChargeRequest(amount = -1))
                    }.constraintViolations.map { it.message },
                ).containsExactly("충전액은 1원 이상이어야 합니다.")
            },
        )
        entityManager.flushAndClear()
        val accountId = accountIdOf(user.id)
        assertAll(
            { assertThat(entityManager.balanceOf(accountId)).isZero() },
            { assertThat(entityManager.countPointHistories(accountId)).isZero() },
        )
    }

    @Test
    fun `a charge that overflows the balance is rejected and changes nothing`() {
        val user = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(amount = Long.MAX_VALUE))
        entityManager.flushAndClear()

        assertThrows<InvalidMoneyException> {
            pointService.charge(user.id, PointChargeRequest(amount = 1))
        }
        entityManager.flushAndClear()
        val accountId = accountIdOf(user.id)
        val balanceAfterFailure = entityManager.balanceOf(accountId)
        val historiesAfterFailure = entityManager.countPointHistories(accountId)

        assertAll(
            { assertThat(balanceAfterFailure).isEqualTo(Long.MAX_VALUE) },
            { assertThat(historiesAfterFailure).isOne() },
        )
    }

    @Test
    fun `charging as an unknown user throws UNAUTHORIZED and creates no account`() {
        val exception = assertThrows<CoreException> {
            pointService.charge(999L, PointChargeRequest(amount = 1_000))
        }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED) },
            { assertThat(entityManager.countPointAccounts(999L)).isZero() },
        )
    }

    @Test
    fun `finding the balance of an unknown user throws UNAUTHORIZED and creates no account`() {
        val exception = assertThrows<CoreException> { pointService.findBalance(999L) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED) },
            { assertThat(entityManager.countPointAccounts(999L)).isZero() },
        )
    }

    /** 사용자는 있는데 계정이 없는 것은 fixture와 데이터의 불일치다. 0원 계정을 만들어 주지 않고 내부 오류다(설계 5.9, 6 끝). */
    @Test
    fun `an existing user without an account is an internal error for both charging and reading, and no account is created`() {
        val user = userFixture.registerUserWithoutAccount()
        entityManager.flushAndClear()

        val chargeException = assertThrows<CoreException> {
            pointService.charge(user.id, PointChargeRequest(amount = 1_000))
        }
        val readException = assertThrows<CoreException> { pointService.findBalance(user.id) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(chargeException.errorType).isEqualTo(ErrorType.POINT_ACCOUNT_MISSING) },
            { assertThat(readException.errorType).isEqualTo(ErrorType.POINT_ACCOUNT_MISSING) },
            { assertThat(entityManager.countPointAccounts(user.id)).isZero() },
        )
    }

    private fun accountIdOf(userId: Long): Long = pointAccountRepository.findByUserId(userId)!!.id
}
