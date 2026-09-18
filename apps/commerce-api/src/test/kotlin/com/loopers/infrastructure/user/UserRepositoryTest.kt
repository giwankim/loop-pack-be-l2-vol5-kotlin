package com.loopers.infrastructure.user

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

/**
 * [UserRepositoryImpl]이 [UserRepository] 계약을 실제 MySQL에서 지키는지 확인한다. 설정과 패키지 위치의 이유는
 * [com.loopers.infrastructure.brand.BrandRepositoryTest]와 같다.
 *
 * 사용자는 실습용 데이터라 이 조각이 묻는 것은 "그 사용자가 있는가" 하나다. 요청자 식별이 그 답에 기댄다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig::class, MySqlTestContainersConfig::class, UserRepositoryImpl::class)
class UserRepositoryTest(
    private val userRepository: UserRepository,
    private val entityManager: EntityManager,
) {
    @Test
    fun `existsById is true for a saved user after flush and clear`() {
        val saved = userRepository.save(User())
        entityManager.flushAndClear()

        assertThat(userRepository.existsById(saved.id)).isTrue()
    }

    @Test
    fun `existsById is false for an unknown id`() {
        assertThat(userRepository.existsById(999L)).isFalse()
    }
}
