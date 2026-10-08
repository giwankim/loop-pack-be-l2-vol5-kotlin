package com.loopers.application.point

import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.point.required.PointAccountRepository
import com.loopers.domain.point.PointAccount
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ApplicationService

/**
 * [PointAccountFinder]의 구현. 요청자는 웹 경계가 이미 받아들였으므로 받은 `userId`를 믿는다(ADR 0015).
 *
 * 계정은 사용자 fixture와 함께 만들어져 있어야 한다. 사용자는 있는데 계정이 없으면 데이터 불일치이므로
 * 0원 계정을 만들어 주지 않고 내부 오류로 답한다(설계 5.9, 6 끝).
 */
@ApplicationService(readOnly = true)
class PointQueryService(
    private val pointAccountRepository: PointAccountRepository,
) : PointAccountFinder {
    override fun findByUser(userId: Long): PointAccount {
        return pointAccountRepository.findByUserId(userId) ?: throw CoreException(ErrorType.POINT_ACCOUNT_MISSING)
    }
}
