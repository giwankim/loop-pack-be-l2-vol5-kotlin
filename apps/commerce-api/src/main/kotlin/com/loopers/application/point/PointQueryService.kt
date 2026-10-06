package com.loopers.application.point

import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.point.provided.PointAccountInfo
import com.loopers.application.point.required.PointAccountRepository
import com.loopers.application.user.provided.UserFinder
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ApplicationService
import org.springframework.transaction.annotation.Transactional

/**
 * [PointAccountFinder]의 구현. 요청자가 있는지는 [UserFinder]에 묻는다. 헤더가 없는 것은 adapter.webapi가 401로 거절하고,
 * 그 사용자가 있는지는 여기서 본다(카탈로그 설계 5.27).
 *
 * 계정은 사용자 fixture와 함께 만들어져 있어야 한다. 사용자는 있는데 계정이 없으면 데이터 불일치이므로
 * 0원 계정을 만들어 주지 않고 내부 오류로 답한다(설계 5.9, 6 끝).
 * 읽기 메서드는 클래스의 `@Transactional`보다 앞서는 `@Transactional(readOnly = true)`를 단다.
 */
@ApplicationService
class PointQueryService(
    private val pointAccountRepository: PointAccountRepository,
    private val userFinder: UserFinder,
) : PointAccountFinder {
    @Transactional(readOnly = true)
    override fun findBalance(userId: Long): PointAccountInfo {
        userFinder.checkExists(userId)
        val account =
            pointAccountRepository.findByUserId(userId) ?: throw CoreException(ErrorType.POINT_ACCOUNT_MISSING)
        return PointAccountInfo.from(account)
    }
}
