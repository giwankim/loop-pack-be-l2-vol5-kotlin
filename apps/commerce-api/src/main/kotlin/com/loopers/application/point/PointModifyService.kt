package com.loopers.application.point

import com.loopers.application.point.provided.PointAccountInfo
import com.loopers.application.point.provided.PointChargeRequest
import com.loopers.application.point.provided.PointCharger
import com.loopers.application.point.required.PointAccountRepository
import com.loopers.application.user.provided.UserFinder
import com.loopers.domain.shared.Money
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [PointCharger]의 구현. 충전이 Request를 받으므로 검증하는 Service다.
 * 요청자와 계정을 보는 규칙은 [PointQueryService]와 같다(카탈로그 설계 5.27, 설계 5.9).
 *
 * 바꿀 계정은 저장소에서 엔티티로 읽는다. [com.loopers.application.point.provided.PointAccountFinder]는
 * 잔액만 담은 [PointAccountInfo]를 돌려주기 때문이다.
 */
@ValidatedApplicationService
class PointModifyService(
    private val pointAccountRepository: PointAccountRepository,
    private val userFinder: UserFinder,
) : PointCharger {
    override fun charge(userId: Long, request: PointChargeRequest): PointAccountInfo {
        userFinder.checkExists(userId)
        val account =
            pointAccountRepository.findByUserId(userId) ?: throw CoreException(ErrorType.POINT_ACCOUNT_MISSING)
        account.charge(Money(request.amount))
        return PointAccountInfo.from(account)
    }
}
