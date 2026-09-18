package com.loopers.application.point

import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.point.PointHistoryRepository
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.UserFixture
import com.loopers.utils.balanceOf
import com.loopers.utils.countPointHistories
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest

/**
 * 충전의 커밋과 롤백을 서비스 트랜잭션 밖에서 본다. 테스트 트랜잭션으로 감싸면 서비스가 그 안에 합류해
 * 커밋도 롤백도 테스트의 것이 되므로, 여기서는 감싸지 않고 서비스가 끝난 뒤 새 트랜잭션에서 테이블을 읽는다.
 * 남은 행은 [DatabaseCleanUp]으로 지운다.
 *
 * 늦은 실패는 이력 저장 경계에 넣는다. 이력의 INSERT가 실제로 나간 뒤 던지므로, 잔액 변경과 이력이
 * 한 트랜잭션에 있지 않으면 어느 한쪽이 남는다(설계 10 트랜잭션 롤백).
 */
@SpringBootTest
class PointServiceTransactionTest(
    private val pointService: PointService,
    private val pointAccountRepository: PointAccountRepository,
    private val userFixture: UserFixture,
    private val databaseCleanUp: DatabaseCleanUp,
    private val entityManager: EntityManager,
) {
    @SpykBean
    private lateinit var pointHistoryRepository: PointHistoryRepository

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `a charge commits the balance and the history together so a new transaction sees both`() {
        val user = userFixture.registerUser()

        pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        val account = pointAccountRepository.findByUserId(user.id)!!

        assertAll(
            { assertThat(entityManager.balanceOf(account.id)).isEqualTo(10_000L) },
            { assertThat(entityManager.countPointHistories(account.id, "charge-001")).isOne() },
        )
    }

    /** 이력의 INSERT까지 나간 뒤 실패해도 잔액과 이력이 함께 되돌아가고, 그 키로 다시 충전할 수 있다. */
    @Test
    fun `a failure after the history insert rolls back the balance and the history and leaves the key reusable`() {
        val user = userFixture.registerUser()
        every { pointHistoryRepository.save(any()) } answers {
            callOriginal()
            throw IllegalStateException("late failure")
        }

        assertThrows<IllegalStateException> {
            pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))
        }
        val account = pointAccountRepository.findByUserId(user.id)!!
        val balanceAfterFailure = entityManager.balanceOf(account.id)
        val historiesAfterFailure = entityManager.countPointHistories(account.id)

        every { pointHistoryRepository.save(any()) } answers { callOriginal() }
        val retried = pointService.charge(user.id, PointChargeRequest(chargeKey = "charge-001", amount = 10_000))

        assertAll(
            { assertThat(balanceAfterFailure).isZero() },
            { assertThat(historiesAfterFailure).isZero() },
            { assertThat(retried.balance).isEqualTo(10_000L) },
            { assertThat(entityManager.balanceOf(account.id)).isEqualTo(10_000L) },
            { assertThat(entityManager.countPointHistories(account.id, "charge-001")).isOne() },
        )
    }
}
