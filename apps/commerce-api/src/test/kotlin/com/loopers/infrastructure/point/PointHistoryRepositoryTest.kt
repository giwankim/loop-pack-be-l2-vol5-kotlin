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
import com.loopers.utils.countPointHistories
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
 * 충전 키의 대소문자 구분은 열의 collation이 정하는 것이라 애플리케이션 코드로는 볼 수 없다. 조회와 유일 제약이
 * 같은 비교를 쓰는지, 계정을 향한 외래 키가 있는지를 여기서 본다(설계 5.8, 12.2).
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
    fun `findByAccountIdAndChargeKey reads a saved charge back after flush and clear`() {
        val account = registerAccount()
        val saved = pointHistoryRepository.save(account.charge(Money(10_000), chargeKey = "charge-001"))
        entityManager.flushAndClear()

        val found = pointHistoryRepository.findByAccountIdAndChargeKey(account.id, "charge-001")

        assertAll(
            { assertThat(found).isNotNull().isNotSameAs(saved) },
            { assertThat(found?.id).isEqualTo(saved.id) },
            { assertThat(found?.type).isEqualTo(PointHistoryType.CHARGE) },
            { assertThat(found?.amount).isEqualTo(Money(10_000)) },
            { assertThat(found?.balanceAfter).isEqualTo(Money(10_000)) },
            { assertThat(found?.chargeKey).isEqualTo("charge-001") },
            { assertThat(found?.account?.id).isEqualTo(account.id) },
            { assertThat(found?.createdAt).isNotNull() },
        )
    }

    @Test
    fun `findByAccountIdAndChargeKey is null for another account's key and for an unknown key`() {
        val account = registerAccount()
        val other = registerAccount()
        pointHistoryRepository.save(account.charge(Money(10_000), chargeKey = "charge-001"))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(pointHistoryRepository.findByAccountIdAndChargeKey(other.id, "charge-001")).isNull() },
            { assertThat(pointHistoryRepository.findByAccountIdAndChargeKey(account.id, "charge-002")).isNull() },
        )
    }

    /** 키는 대소문자를 구분한다. 서버 기본 collation은 무시하므로 열이 스스로 정해야 한다(설계 5.8). */
    @Test
    fun `keys that differ only in case are different keys for both lookup and uniqueness`() {
        val account = registerAccount()
        val upper = pointHistoryRepository.save(account.charge(Money(1_000), chargeKey = "Charge-A"))
        val lower = pointHistoryRepository.save(account.charge(Money(2_000), chargeKey = "charge-a"))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(pointHistoryRepository.findByAccountIdAndChargeKey(account.id, "Charge-A")?.id).isEqualTo(upper.id) },
            { assertThat(pointHistoryRepository.findByAccountIdAndChargeKey(account.id, "charge-a")?.id).isEqualTo(lower.id) },
            { assertThat(pointHistoryRepository.findByAccountIdAndChargeKey(account.id, "CHARGE-A")).isNull() },
            { assertThat(entityManager.countPointHistories(account.id, "Charge-A")).isOne() },
            { assertThat(entityManager.countPointHistories(account.id)).isEqualTo(2L) },
        )
    }

    /** 식별자가 IDENTITY라 저장이 곧 INSERT이므로 같은 계정의 같은 키는 flush를 기다리지 않고 바로 거절된다. */
    @Test
    fun `saving the same key twice for one account violates the unique constraint`() {
        val account = registerAccount()
        pointHistoryRepository.save(account.charge(Money(1_000), chargeKey = "charge-001"))

        assertThrows<DataIntegrityViolationException> {
            pointHistoryRepository.save(account.charge(Money(1_000), chargeKey = "charge-001"))
        }
    }

    @Test
    fun `different accounts may use the same key`() {
        val account = registerAccount()
        val other = registerAccount()
        pointHistoryRepository.save(account.charge(Money(1_000), chargeKey = "charge-001"))
        pointHistoryRepository.save(other.charge(Money(2_000), chargeKey = "charge-001"))
        entityManager.flushAndClear()

        assertAll(
            {
                assertThat(pointHistoryRepository.findByAccountIdAndChargeKey(account.id, "charge-001")?.amount)
                    .isEqualTo(Money(1_000))
            },
            {
                assertThat(pointHistoryRepository.findByAccountIdAndChargeKey(other.id, "charge-001")?.amount)
                    .isEqualTo(Money(2_000))
            },
        )
    }

    /**
     * 계정 행이 없는 이력은 DB가 거절한다. 프록시로 식별자만 실어 INSERT까지 보낸다. 프록시의 `charge`를 부르면
     * 계정을 읽으려다 먼저 실패하므로, 계정이 만드는 기록을 여기서는 팩토리로 직접 만든다.
     */
    @Test
    fun `saving a history for an account that does not exist violates the foreign key`() {
        val missingAccount = entityManager.getReference(PointAccount::class.java, 999L)
        val history =
            PointHistory.charge(missingAccount, amount = Money(1_000), balanceAfter = Money(1_000), chargeKey = "charge-001")

        assertThrows<DataIntegrityViolationException> { pointHistoryRepository.save(history) }
    }

    @Test
    fun `the account foreign key, the case-sensitive key column, and the unique key exist in the database`() {
        val foreignKey = entityManager
            .createNativeQuery(
                "select referenced_table_name, referenced_column_name from information_schema.key_column_usage " +
                    "where table_schema = database() and table_name = 'point_history' " +
                    "and constraint_name = 'fk_point_history_point_account'",
            )
            .singleResult as Array<*>
        val collation = entityManager
            .createNativeQuery(
                "select collation_name from information_schema.columns where table_schema = database() " +
                    "and table_name = 'point_history' and column_name = 'charge_key'",
            )
            .singleResult
        val uniqueColumns = entityManager
            .createNativeQuery(
                "select column_name from information_schema.statistics where table_schema = database() " +
                    "and table_name = 'point_history' and index_name = 'uk_point_history_point_account_id_charge_key' " +
                    "and non_unique = 0 order by seq_in_index",
            )
            .resultList

        assertAll(
            { assertThat(foreignKey[0]).isEqualTo("point_account") },
            { assertThat(foreignKey[1]).isEqualTo("id") },
            { assertThat(collation).isEqualTo("utf8mb4_bin") },
            { assertThat(uniqueColumns).containsExactly("point_account_id", "charge_key") },
        )
    }

    private fun registerAccount(): PointAccount = pointAccountRepository.save(PointAccount(userRepository.save(User())))
}
