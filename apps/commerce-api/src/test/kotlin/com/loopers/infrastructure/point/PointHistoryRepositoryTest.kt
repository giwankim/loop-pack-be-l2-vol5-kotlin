package com.loopers.infrastructure.point

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.point.PointHistory
import com.loopers.domain.point.PointHistoryRepository
import com.loopers.domain.point.PointHistoryType
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.infrastructure.user.UserRepositoryImpl
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

/**
 * [PointHistoryRepositoryImpl]이 [PointHistoryRepository] 계약을 실제 MySQL에서 지키는지 확인한다. 설정과 패키지 위치의 이유는
 * [com.loopers.infrastructure.brand.BrandRepositoryTest]와 같다.
 *
 * 저장한 이력이 열마다 그대로 읽히는지, 계정을 향한 외래 키가 있는지를 여기서 본다(설계 12.1).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    DataSourceConfig::class,
    MySqlTestContainersConfig::class,
    UserRepositoryImpl::class,
    PointAccountRepositoryImpl::class,
    PointHistoryRepositoryImpl::class,
)
class PointHistoryRepositoryTest(
    private val pointHistoryRepository: PointHistoryRepository,
    private val pointAccountRepository: PointAccountRepository,
    private val userRepository: UserRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `a saved charge reads back after flush and clear`() {
        val account = registerAccount()
        val saved = pointHistoryRepository.save(account.charge(Money(10_000)))
        entityManager.flushAndClear()

        val found = entityManager.find(PointHistory::class.java, saved.id)

        assertAll(
            { assertThat(found).isNotNull().isNotSameAs(saved) },
            { assertThat(found?.id).isEqualTo(saved.id) },
            { assertThat(found?.type).isEqualTo(PointHistoryType.CHARGE) },
            { assertThat(found?.amount).isEqualTo(Money(10_000)) },
            { assertThat(found?.balanceAfter).isEqualTo(Money(10_000)) },
            { assertThat(found?.account?.id).isEqualTo(account.id) },
            { assertThat(found?.createdAt).isNotNull() },
        )
    }

    /**
     * 계정 행이 없는 이력은 DB가 거절한다. 프록시로 식별자만 실어 INSERT까지 보낸다. 프록시의 `charge`를 부르면
     * 계정을 읽으려다 먼저 실패하므로, 계정이 만드는 기록을 여기서는 팩토리로 직접 만든다.
     */
    @Test
    fun `saving a history for an account that does not exist violates the foreign key`() {
        val missingAccount = entityManager.getReference(PointAccount::class.java, 999L)
        val history = PointHistory.charge(missingAccount, amount = Money(1_000), balanceAfter = Money(1_000))

        assertThrows<DataIntegrityViolationException> { pointHistoryRepository.save(history) }
    }

    @Test
    fun `the account foreign key exists in the database`() {
        val foreignKey = entityManager
            .createNativeQuery(
                "select referenced_table_name, referenced_column_name from information_schema.key_column_usage " +
                    "where table_schema = database() and table_name = 'point_history' " +
                    "and constraint_name = 'fk_point_history_point_account'",
            )
            .singleResult as Array<*>

        assertAll(
            { assertThat(foreignKey[0]).isEqualTo("point_account") },
            { assertThat(foreignKey[1]).isEqualTo("id") },
        )
    }

    private fun registerAccount(): PointAccount = pointAccountRepository.save(PointAccount(userRepository.save(User())))
}
