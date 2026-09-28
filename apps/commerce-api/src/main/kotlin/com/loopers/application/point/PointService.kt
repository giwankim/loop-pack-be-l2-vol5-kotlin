package com.loopers.application.point

import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.shared.Money
import com.loopers.domain.user.UserRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

/**
 * 포인트 충전과 잔액 조회. [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다.
 * 헤더가 없는 것은 interfaces가 401로 거절하고, 그 사용자가 있는지는 여기서 본다(카탈로그 설계 5.27).
 *
 * 계정은 사용자 fixture와 함께 만들어져 있어야 한다. 사용자는 있는데 계정이 없으면 데이터 불일치이므로
 * 0원 계정을 만들어 주지 않고 내부 오류로 답한다(설계 5.9, 6 끝).
 */
@Service
@Validated
class PointService(
    private val pointAccountRepository: PointAccountRepository,
    private val userRepository: UserRepository,
) {
    /**
     * 충전한다. 잔액만 바꾸며 충전의 기록은 따로 남기지 않는다(ADR 0006).
     * 요청마다 새 충전이다. 같은 충전액을 다시 보내면 다시 충전된다(ADR 0005).
     */
    @Transactional
    fun charge(userId: Long, @Valid request: PointChargeRequest): PointAccountInfo {
        checkUserExists(userId)
        val account = findAccount(userId)
        account.charge(Money(request.amount))
        return PointAccountInfo.from(account)
    }

    /** 요청자의 현재 잔액. */
    @Transactional(readOnly = true)
    fun findBalance(userId: Long): PointAccountInfo {
        checkUserExists(userId)
        return PointAccountInfo.from(findAccount(userId))
    }

    /** 요청자가 가리키는 사용자가 없으면 요청자가 없는 것이다. */
    private fun checkUserExists(userId: Long) {
        if (!userRepository.existsById(userId)) {
            throw CoreException(ErrorType.UNAUTHORIZED)
        }
    }

    private fun findAccount(userId: Long): PointAccount =
        pointAccountRepository.findByUserId(userId) ?: throw CoreException(ErrorType.POINT_ACCOUNT_MISSING)
}
