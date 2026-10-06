package com.loopers.application.point.provided

import com.loopers.application.point.required.PointAccountRepository
import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.user.UserFixture
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.balanceOf
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * [PointCharger]의 커밋을 포트의 트랜잭션 밖에서 본다. 테스트 트랜잭션으로 감싸면 포트가 그 안에 합류해
 * 커밋도 롤백도 테스트의 것이 되므로, 여기서는 감싸지 않고 포트가 끝난 뒤 새 트랜잭션에서 테이블을 읽는다.
 * 같은 까닭으로 테스트 트랜잭션을 거는 [com.loopers.support.stereotype.ApplicationServiceTest]를 달지 않는다.
 * 남은 행은 [DatabaseCleanUp]으로 지운다.
 *
 * 충전은 `point_account` 한 행만 바꾸므로 함께 되돌릴 다른 쓰기가 없다. 늦은 실패의 롤백은 여러 행을 쓰는 확정에서
 * 본다([com.loopers.adapter.webapi.v1.order.OrderConfirmationApiMockMvcTest], 설계 18.1).
 */
@SpringBootTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class PointChargerTransactionTest(
    private val pointCharger: PointCharger,
    private val pointAccountRepository: PointAccountRepository,
    private val userFixture: UserFixture,
    private val databaseCleanUp: DatabaseCleanUp,
    private val entityManager: EntityManager,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `a charge commits the balance so a new transaction sees it`() {
        val user = userFixture.registerUser()

        pointCharger.charge(user.id, createPointChargeRequest(amount = 10_000))
        val account = pointAccountRepository.findByUserId(user.id)!!

        assertThat(entityManager.balanceOf(account.id)).isEqualTo(10_000L)
    }
}
