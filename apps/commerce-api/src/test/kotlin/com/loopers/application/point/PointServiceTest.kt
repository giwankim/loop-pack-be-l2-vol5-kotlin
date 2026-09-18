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
    fun `charging adds to the balance and leaves one CHARGE history with the key, the amount, and the balance after`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()

        val info = pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        entityManager.flushAndClear()
        val account = pointAccountRepository.findByUserId(user.id)!!

        assertAll(
            { assertThat(info.balance).isEqualTo(10_000L) },
            { assertThat(entityManager.balanceOf(account.id)).isEqualTo(10_000L) },
            { assertThat(entityManager.countPointHistories(account.id, "charge-001")).isOne() },
            { assertThat(historyRow(account.id, "charge-001")).containsExactly("CHARGE", 10_000L, 10_000L) },
        )
    }

    @Test
    fun `a second charge with another key adds to the balance and leaves a second history`() {
        val user = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        entityManager.flushAndClear()

        val info = pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-002", amount = 500))
        entityManager.flushAndClear()
        val account = pointAccountRepository.findByUserId(user.id)!!

        assertAll(
            { assertThat(info.balance).isEqualTo(10_500L) },
            { assertThat(entityManager.balanceOf(account.id)).isEqualTo(10_500L) },
            { assertThat(entityManager.countPointHistories(account.id)).isEqualTo(2L) },
            { assertThat(historyRow(account.id, "charge-002")).containsExactly("CHARGE", 500L, 10_500L) },
        )
    }

    /** 같은 키·같은 충전액의 재요청은 첫 결과를 돌려주고 잔액도 이력도 바꾸지 않는다(설계 5.8). */
    @Test
    fun `charging again with the same key and amount returns the first balance and changes nothing`() {
        val user = userFixture.registerUser()
        val first = pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        entityManager.flushAndClear()

        val replayed = pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        entityManager.flushAndClear()
        val account = pointAccountRepository.findByUserId(user.id)!!

        assertAll(
            { assertThat(replayed).isEqualTo(first) },
            { assertThat(entityManager.balanceOf(account.id)).isEqualTo(10_000L) },
            { assertThat(entityManager.countPointHistories(account.id)).isOne() },
        )
    }

    /** 충전 뒤 잔액이 더 늘어도 그 충전의 재요청은 당시 잔액이다. 현재 잔액은 조회가 답한다(ADR 0004). */
    @Test
    fun `a replay returns the balance right after that charge even after a later charge`() {
        val user = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-002", amount = 500))
        entityManager.flushAndClear()

        val replayed = pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))

        assertAll(
            { assertThat(replayed.balance).isEqualTo(10_000L) },
            { assertThat(pointService.findBalance(user.id).balance).isEqualTo(10_500L) },
        )
    }

    @Test
    fun `charging again with the same key and another amount throws IDEMPOTENCY_KEY_CONFLICT and changes nothing`() {
        val user = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        entityManager.flushAndClear()

        val exception = assertThrows<CoreException> {
            pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 20_000))
        }
        entityManager.flushAndClear()
        val account = pointAccountRepository.findByUserId(user.id)!!

        assertAll(
            { assertThat(exception.errorType).isEqualTo(ErrorType.IDEMPOTENCY_KEY_CONFLICT) },
            { assertThat(entityManager.balanceOf(account.id)).isEqualTo(10_000L) },
            { assertThat(entityManager.countPointHistories(account.id)).isOne() },
        )
    }

    /** 키의 범위는 사용자다. 다른 사용자의 같은 키는 남의 충전과 무관하다(설계 5.8). */
    @Test
    fun `the same key charges each user independently`() {
        val user = userFixture.registerUser()
        val other = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        entityManager.flushAndClear()

        val info = pointService.charge(other.id, PointChargeRequest(chargeKey = "charge-001", amount = 3_000))

        assertAll(
            { assertThat(info.balance).isEqualTo(3_000L) },
            { assertThat(pointService.findBalance(user.id).balance).isEqualTo(10_000L) },
            { assertThat(pointService.findBalance(other.id).balance).isEqualTo(3_000L) },
        )
    }

    @Test
    fun `keys that differ only in case are different charges`() {
        val user = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(chargeKey = "Charge-A", amount = 1_000))
        entityManager.flushAndClear()

        val info = pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-a", amount = 2_000))
        entityManager.flushAndClear()
        val account = pointAccountRepository.findByUserId(user.id)!!

        assertAll(
            { assertThat(info.balance).isEqualTo(3_000L) },
            { assertThat(entityManager.countPointHistories(account.id)).isEqualTo(2L) },
        )
    }

    @Test
    fun `findBalance is zero for a fresh account and the current balance after charges`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()
        val fresh = pointService.findBalance(user.id)

        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-002", amount = 500))
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

        val info = pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 1_000_000_001))
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
                        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 0))
                    }.constraintViolations.map { it.message },
                ).containsExactly("충전액은 1원 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = -1))
                    }.constraintViolations.map { it.message },
                ).containsExactly("충전액은 1원 이상이어야 합니다.")
            },
        )
        entityManager.flushAndClear()
        val account = pointAccountRepository.findByUserId(user.id)!!
        assertAll(
            { assertThat(entityManager.balanceOf(account.id)).isZero() },
            { assertThat(entityManager.countPointHistories(account.id)).isZero() },
        )
    }

    /** 키 형식은 HTTP 헤더가 먼저 거르지만 Controller를 거치지 않는 호출도 같은 규칙을 받는다(카탈로그 설계 5.25). */
    @Test
    fun `a key outside the allowed form is rejected by request validation`() {
        val user = userFixture.registerUser()
        entityManager.flushAndClear()

        assertAll(
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        pointService.charge(user.id, PointChargeRequest(chargeKey = "", amount = 1_000))
                    }.constraintViolations.map { it.message },
                ).containsExactly("충전 키는 1자 이상 128자 이하의 영문·숫자·하이픈·밑줄이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge 001", amount = 1_000))
                    }.constraintViolations.map { it.message },
                ).containsExactly("충전 키는 1자 이상 128자 이하의 영문·숫자·하이픈·밑줄이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        pointService.charge(user.id, PointChargeRequest(chargeKey = "k".repeat(129), amount = 1_000))
                    }.constraintViolations.map { it.message },
                ).containsExactly("충전 키는 1자 이상 128자 이하의 영문·숫자·하이픈·밑줄이어야 합니다.")
            },
        )
    }

    /** 실패한 요청은 키를 쓰지 않는다. 고쳐서 같은 키로 다시 보낼 수 있다(설계 5.8). */
    @Test
    fun `a charge that overflows the balance is rejected, changes nothing, and leaves the key reusable`() {
        val user = userFixture.registerUser()
        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = Long.MAX_VALUE))
        entityManager.flushAndClear()

        assertThrows<InvalidMoneyException> {
            pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-002", amount = 1))
        }
        entityManager.flushAndClear()
        val account = pointAccountRepository.findByUserId(user.id)!!
        val balanceAfterFailure = entityManager.balanceOf(account.id)
        val historiesAfterFailure = entityManager.countPointHistories(account.id)

        assertAll(
            { assertThat(balanceAfterFailure).isEqualTo(Long.MAX_VALUE) },
            { assertThat(historiesAfterFailure).isOne() },
            { assertThat(entityManager.countPointHistories(account.id, "charge-002")).isZero() },
        )
    }

    @Test
    fun `charging as an unknown user throws UNAUTHORIZED and creates no account`() {
        val exception = assertThrows<CoreException> {
            pointService.charge(999L, PointChargeRequest(chargeKey = "charge-001", amount = 1_000))
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

    /** 사용자는 있는데 계정이 없는 것은 fixture와 데이터의 불일치다. 0원 계정을 만들어 주지 않고 내부 오류다(설계 5.9). */
    @Test
    fun `an existing user without an account is an internal error for both charging and reading, and no account is created`() {
        val user = userFixture.registerUserWithoutAccount()
        entityManager.flushAndClear()

        val chargeException = assertThrows<CoreException> {
            pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 1_000))
        }
        val readException = assertThrows<CoreException> { pointService.findBalance(user.id) }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(chargeException.errorType).isEqualTo(ErrorType.POINT_ACCOUNT_MISSING) },
            { assertThat(readException.errorType).isEqualTo(ErrorType.POINT_ACCOUNT_MISSING) },
            { assertThat(entityManager.countPointAccounts(user.id)).isZero() },
        )
    }

    /** 이력 한 행의 종류·금액·직후 잔액. */
    private fun historyRow(accountId: Long, chargeKey: String): List<Any?> {
        val row = entityManager
            .createNativeQuery(
                "select type, amount, balance_after from point_history " +
                    "where point_account_id = :accountId and charge_key = :chargeKey",
            )
            .setParameter("accountId", accountId)
            .setParameter("chargeKey", chargeKey)
            .singleResult as Array<*>
        return listOf(row[0], (row[1] as Number).toLong(), (row[2] as Number).toLong())
    }
}
