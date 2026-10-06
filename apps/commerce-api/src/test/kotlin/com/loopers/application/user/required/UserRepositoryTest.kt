package com.loopers.application.user.required

import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.user.User
import com.loopers.support.flushAndClear
import com.loopers.testcontainers.MySqlTestContainersConfig
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import

/**
 * Spring Data가 만든 [UserRepository]가 실제 MySQL에서 계약을 지키는지 확인한다. 구현 클래스가 없어 가져올 것은
 * 데이터소스 설정과 컨테이너 설정뿐이다. 저장소는 슬라이스가 `com.loopers` 아래에서 찾아 등록한다.
 * 내장 DB로 바꾸지 않게 하고, 테스트마다 트랜잭션이 롤백되어 정리가 필요 없다.
 * 테스트는 splearn의 저장소 테스트처럼 포트가 선언된 `required` 패키지에 둔다.
 *
 * 사용자는 실습용 데이터라 이 조각이 묻는 것은 "그 사용자가 있는가" 하나다. 요청자 식별이 그 답에 기댄다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig::class, MySqlTestContainersConfig::class)
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
