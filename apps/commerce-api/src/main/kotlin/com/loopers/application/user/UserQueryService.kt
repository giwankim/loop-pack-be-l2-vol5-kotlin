package com.loopers.application.user

import com.loopers.application.user.provided.UserFinder
import com.loopers.application.user.required.UserRepository
import com.loopers.support.stereotype.ApplicationService

/** [UserFinder]의 구현. 웹 경계가 컨트롤러 앞에서 부르므로 자기 읽기 트랜잭션을 연다. */
@ApplicationService(readOnly = true)
class UserQueryService(
    private val userRepository: UserRepository,
) : UserFinder {
    override fun exists(userId: Long): Boolean {
        return userRepository.existsById(userId)
    }
}
