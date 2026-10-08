package com.loopers.application.point

import com.loopers.application.point.provided.PointAccountInfo
import com.loopers.application.point.provided.PointChargeRequest
import com.loopers.application.point.provided.PointCharger
import com.loopers.application.point.provided.PointDeductor
import com.loopers.application.point.required.PointAccountRepository
import com.loopers.domain.point.PointAccount
import com.loopers.domain.shared.Money
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [PointCharger]와 [PointDeductor]의 구현. 충전이 Request를 받으므로 검증하는 Service다.
 * 계정을 보는 규칙은 [PointQueryService]와 같다(설계 5.9). 요청자는 웹 경계가 이미 받아들였으므로 받은 `userId`를 믿는다(ADR 0015).
 *
 * 바꿀 계정은 저장소에서 엔티티로 읽는다. [com.loopers.application.point.provided.PointAccountFinder]는
 * 잔액만 담은 [PointAccountInfo]를 돌려주기 때문이다.
 */
@ValidatedApplicationService
class PointModifyService(
    private val pointAccountRepository: PointAccountRepository,
) : PointCharger,
    PointDeductor {
    override fun charge(userId: Long, request: PointChargeRequest): PointAccountInfo {
        val account = findAccountOrThrow(userId)
        account.charge(Money(request.amount))
        return PointAccountInfo.from(account)
    }

    /** 주문 확정이 부른다. 결제의 기록은 확정된 주문이므로 차감의 기록은 따로 남기지 않는다(ADR 0006). */
    override fun deduct(userId: Long, amount: Money) {
        findAccountOrThrow(userId).pay(amount)
    }

    private fun findAccountOrThrow(userId: Long): PointAccount =
        pointAccountRepository.findByUserId(userId) ?: throw CoreException(ErrorType.POINT_ACCOUNT_MISSING)
}
