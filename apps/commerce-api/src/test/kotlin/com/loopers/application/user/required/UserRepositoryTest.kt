package com.loopers.application.user.required

import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Spring Data가 만든 [UserRepository]가 실제 MySQL에서 계약을 지키는지 확인한다. 구현 클래스가 없어
 * [BaseRepositoryTest]의 설정 밖에 가져올 것이 없다. 저장소는 슬라이스가 `com.loopers` 아래에서 찾아 등록한다.
 * 테스트는 splearn의 저장소 테스트처럼 포트가 선언된 `required` 패키지에 둔다.
 *
 * 사용자는 실습용 데이터라 이 조각이 묻는 것은 "그 사용자가 있는가" 하나다. 요청자 식별이 그 답에 기댄다.
 */
class UserRepositoryTest(
    private val userRepository: UserRepository,
) : BaseRepositoryTest() {
    @Test
    fun `existsById is true for a saved user after flush and clear`() {
        prepareUser()
        entityManager.flushAndClear()

        assertThat(userRepository.existsById(user.id)).isTrue()
    }

    @Test
    fun `existsById is false for an unknown id`() {
        assertThat(userRepository.existsById(999L)).isFalse()
    }

    @Test
    fun `existsById is false for a deleted user`() {
        prepareUser()
        deleteUser()
        entityManager.flushAndClear()

        assertThat(userRepository.existsById(user.id)).isFalse()
    }
}
