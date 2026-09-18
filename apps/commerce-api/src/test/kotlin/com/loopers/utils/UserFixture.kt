package com.loopers.utils

import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import org.springframework.stereotype.Component

/**
 * 실습용 사용자 fixture. 사용자를 만들 때 잔액 0원의 포인트 계정을 함께 만든다(설계 5.9, Q17).
 * 조회나 충전이 없는 계정을 만들어 주지 않으므로, 포인트를 다루는 테스트는 사용자를 이것으로 준비한다.
 * 사용자를 만드는 API가 없어 이 준비는 테스트에만 있다.
 */
@Component
class UserFixture(
    private val userRepository: UserRepository,
    private val pointAccountRepository: PointAccountRepository,
) {
    /** 사용자 하나와 그 사용자의 0원 계정. */
    fun registerUser(): User = userRepository.save(User()).also { pointAccountRepository.save(PointAccount(it)) }

    /** 계정 없이 사용자만. fixture와 데이터가 어긋난 경우를 만들 때만 쓴다. */
    fun registerUserWithoutAccount(): User = userRepository.save(User())
}
