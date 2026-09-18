package com.loopers.infrastructure.point

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.infrastructure.user.UserRepositoryImpl
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

/**
 * [PointAccountRepositoryImpl]이 [PointAccountRepository] 계약을 실제 MySQL에서 지키는지 확인한다. 설정과 패키지 위치의 이유는
 * [com.loopers.infrastructure.brand.BrandRepositoryTest]와 같다.
 *
 * 사용자당 계정 하나와 사용자를 향한 외래 키는 DB가 지키는 약속이라 여기서 본다. 제약이 실제로 만들어졌는지는
 * `information_schema`에서도 확인한다(설계 10 DB 참조 무결성).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig::class, MySqlTestContainersConfig::class, UserRepositoryImpl::class, PointAccountRepositoryImpl::class)
class PointAccountRepositoryTest(
    private val pointAccountRepository: PointAccountRepository,
    private val userRepository: UserRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `findByUserId reads a saved account back with its balance after flush and clear`() {
        val user = userRepository.save(User())
        val saved = pointAccountRepository.save(PointAccount(user).apply { charge(Money(10_000), chargeKey = "charge-001") })
        entityManager.flushAndClear()

        val found = pointAccountRepository.findByUserId(user.id)

        assertAll(
            { assertThat(found).isNotNull().isNotSameAs(saved) },
            { assertThat(found?.id).isEqualTo(saved.id) },
            { assertThat(found?.userId).isEqualTo(user.id) },
            { assertThat(found?.balance).isEqualTo(Money(10_000)) },
            { assertThat(found?.createdAt).isNotNull() },
        )
    }

    /** 사용자는 식별자뿐이라 계정을 읽을 때 사용자 행까지 읽을 까닭이 없다. 식별자는 프록시가 들고 있다. */
    @Test
    fun `findByUserId gives the user id without loading the user`() {
        val user = userRepository.save(User())
        pointAccountRepository.save(PointAccount(user))
        entityManager.flushAndClear()

        val found = pointAccountRepository.findByUserId(user.id)!!

        assertAll(
            { assertThat(Hibernate.isInitialized(found.user)).isFalse() },
            { assertThat(found.userId).isEqualTo(user.id) },
            { assertThat(Hibernate.isInitialized(found.user)).isFalse() },
        )
    }

    @Test
    fun `findByUserId is null for a user without an account and for an unknown user`() {
        val user = userRepository.save(User())
        val other = userRepository.save(User())
        pointAccountRepository.save(PointAccount(other))
        entityManager.flushAndClear()

        assertAll(
            { assertThat(pointAccountRepository.findByUserId(user.id)).isNull() },
            { assertThat(pointAccountRepository.findByUserId(999L)).isNull() },
        )
    }

    /** 식별자가 IDENTITY라 저장이 곧 INSERT이므로 두 번째 계정은 flush를 기다리지 않고 바로 거절된다. */
    @Test
    fun `saving a second account for the same user violates the unique constraint`() {
        val user = userRepository.save(User())
        pointAccountRepository.save(PointAccount(user))

        assertThrows<DataIntegrityViolationException> { pointAccountRepository.save(PointAccount(user)) }
    }

    /** 사용자 행이 없는 계정은 DB가 거절한다. 프록시로 식별자만 실어 INSERT까지 보낸다. */
    @Test
    fun `saving an account for a user that does not exist violates the foreign key`() {
        val missingUser = entityManager.getReference(User::class.java, 999L)

        assertThrows<DataIntegrityViolationException> { pointAccountRepository.save(PointAccount(missingUser)) }
    }

    @Test
    fun `the user foreign key and the unique key exist in the database`() {
        val foreignKey = entityManager
            .createNativeQuery(
                "select referenced_table_name, referenced_column_name from information_schema.key_column_usage " +
                    "where table_schema = database() and table_name = 'point_account' " +
                    "and constraint_name = 'fk_point_account_user'",
            )
            .singleResult as Array<*>
        val uniqueColumns = entityManager
            .createNativeQuery(
                "select column_name from information_schema.statistics where table_schema = database() " +
                    "and table_name = 'point_account' and index_name = 'uk_point_account_user_id' and non_unique = 0",
            )
            .resultList

        assertAll(
            { assertThat(foreignKey[0]).isEqualTo("users") },
            { assertThat(foreignKey[1]).isEqualTo("id") },
            { assertThat(uniqueColumns).containsExactly("user_id") },
        )
    }
}
