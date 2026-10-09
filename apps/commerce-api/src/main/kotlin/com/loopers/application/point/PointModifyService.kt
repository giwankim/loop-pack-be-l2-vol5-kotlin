package com.loopers.application.point

import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.point.provided.PointChargeRequest
import com.loopers.application.point.provided.PointCharger
import com.loopers.application.point.provided.PointDeductor
import com.loopers.application.point.required.PointAccountRepository
import com.loopers.domain.point.PointAccount
import com.loopers.domain.shared.Money
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [PointCharger]와 [PointDeductor]의 구현. 충전이 Request를 받으므로 검증하는 Service다.
 * 바꿀 계정은 같은 조각의 [PointAccountFinder.findForUpdate]로 얻으므로, 계정이 없을 때의 거절도 조회와 같다(설계 5.9, ADR 0014).
 * 그 읽기가 계정 행을 잠가, 같은 계정의 충전과 결제는 한쪽이 커밋할 때까지 기다려 차례로 지나가고
 * 뒤에 온 쪽은 앞선 쪽이 커밋한 잔액에서 더하거나 뺀다(ADR 0019).
 * 요청자는 웹 경계가 이미 받아들였으므로 받은 `userId`를 믿는다(ADR 0015).
 */
@ValidatedApplicationService
class PointModifyService(
    private val pointAccountFinder: PointAccountFinder,
    private val pointAccountRepository: PointAccountRepository,
) : PointCharger,
    PointDeductor {
    override fun charge(userId: Long, request: PointChargeRequest): PointAccount {
        val account = pointAccountFinder.findForUpdate(userId)
        account.charge(Money(request.amount))

        return pointAccountRepository.save(account)
    }

    /**
     * 주문 확정이 부른다. 결제의 기록은 확정된 주문이므로 차감의 기록은 따로 남기지 않는다(ADR 0006).
     * 확정이 주문과 상품을 잠근 뒤에 부르므로 계정은 그 다음에 잠긴다(ADR 0019 규칙 3).
     */
    override fun deduct(userId: Long, amount: Money) {
        val account = pointAccountFinder.findForUpdate(userId)
        account.pay(amount)

        pointAccountRepository.save(account)
    }
}
