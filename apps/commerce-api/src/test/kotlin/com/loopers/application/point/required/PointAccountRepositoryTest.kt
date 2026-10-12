package com.loopers.application.point.required

import com.loopers.domain.point.PointAccount
import com.loopers.domain.shared.Money
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException

/**
 * Spring Data가 만든 [PointAccountRepository]가 실제 MySQL에서 계약을 지키는지 확인한다. 패키지 위치의 이유는
 * [com.loopers.application.user.required.UserRepositoryTest]와 같다.
 *
 * 사용자당 계정 하나와 사용자를 향한 외래 키는 DB가 지키는 약속이라 여기서 본다. 제약이 실제로 만들어졌는지는
 * `information_schema`에서도 확인한다(설계 10 DB 참조 무결성).
 */
class PointAccountRepositoryTest(
    private val pointAccountRepository: PointAccountRepository,
) : BaseRepositoryTest() {
    @Test
    fun `findByUserId reads a saved account back with its balance after flush and clear`() {
        prepareUser()
        charge(amount = Money(10_000))
        entityManager.flushAndClear()

        val found = pointAccountRepository.findByUserId(user.id)

        assertThat(found).isNotNull().isNotSameAs(pointAccount)
        assertThat(found?.id).isEqualTo(pointAccount.id)
        assertThat(found?.userId).isEqualTo(user.id)
        assertThat(found?.balance).isEqualTo(Money(10_000))
        assertThat(found?.createdAt).isNotNull()
    }

    @Test
    fun `findByUserId is null for a user without an account and for an unknown user`() {
        val userWithoutAccount = prepareUserWithoutAccount()
        prepareUser()
        entityManager.flushAndClear()

        assertThat(pointAccountRepository.findByUserId(userWithoutAccount.id)).isNull()
        assertThat(pointAccountRepository.findByUserId(999L)).isNull()
    }

    @Test
    fun `findByUserId is null for a deleted account`() {
        prepareUser()
        deletePointAccount()
        entityManager.flushAndClear()

        assertThat(pointAccountRepository.findByUserId(user.id)).isNull()
    }

    /** 식별자가 IDENTITY라 저장이 곧 INSERT이므로 두 번째 계정은 flush를 기다리지 않고 바로 거절된다. */
    @Test
    fun `saving a second account for the same user violates the unique constraint`() {
        prepareUser()

        assertThrows<DataIntegrityViolationException> { pointAccountRepository.save(PointAccount(user.id)) }
    }

    /** 사용자 행이 없는 계정은 DB가 거절한다. 외래 키는 연관이 아니라 `scalar-foreign-keys.sql`이 만든다(ADR 0014). */
    @Test
    fun `saving an account for a user that does not exist violates the foreign key`() {
        assertThrows<DataIntegrityViolationException> { pointAccountRepository.save(PointAccount(userId = 999L)) }
    }

    @Test
    fun `the user foreign key and the unique key exist in the database`() {
        val foreignKey = entityManager
            .createNativeQuery(
                "select referenced_table_name, referenced_column_name from information_schema.key_column_usage " +
                    "where table_schema = database() and table_name = 'point_account' " +
                    "and constraint_name = 'FK_POINT_ACCOUNT_USER'",
            )
            .singleResult as Array<*>
        val uniqueColumns = entityManager
            .createNativeQuery(
                "select column_name from information_schema.statistics where table_schema = database() " +
                    "and table_name = 'point_account' and index_name = 'UK_POINT_ACCOUNT_USER_ID' and non_unique = 0",
            )
            .resultList

        assertThat(foreignKey[0]).isEqualTo("users")
        assertThat(foreignKey[1]).isEqualTo("id")
        assertThat(uniqueColumns).containsExactly("user_id")
    }
}
