package com.loopers.application.user

import com.loopers.application.user.provided.UserFinder
import com.loopers.application.user.required.UserRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ApplicationService

/** [UserFinder]의 구현. 부르는 쪽이 이미 연 트랜잭션에 참여한다. */
@ApplicationService(readOnly = true)
class UserQueryService(
    private val userRepository: UserRepository,
) : UserFinder {
    override fun checkExists(userId: Long) {
        if (!userRepository.existsById(userId)) throw CoreException(ErrorType.UNAUTHORIZED)
    }
}
