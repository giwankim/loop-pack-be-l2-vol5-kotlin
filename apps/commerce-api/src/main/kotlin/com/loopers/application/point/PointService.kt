package com.loopers.application.point

import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.point.PointHistoryRepository
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
 * 0원 계정을 만들어 주지 않고 내부 오류로 답한다(설계 5.9).
 */
@Service
@Validated
class PointService(
    private val pointAccountRepository: PointAccountRepository,
    private val pointHistoryRepository: PointHistoryRepository,
    private val userRepository: UserRepository,
) {
    /**
     * 충전한다. 잔액 변경과 그 충전의 CHARGE 이력을 한 트랜잭션으로 저장한다(설계 5.5).
     *
     * 같은 사용자의 같은 충전 키로 성공한 기록이 있으면 현재 잔액 대신 그때의 결과를 돌려준다. 충전액이 다르면
     * 다른 의도이므로 거절한다. 실패한 요청은 기록을 남기지 않으므로 키를 쓰지 않는다(설계 5.8, ADR 0004).
     * 요청자 확인과 입력 검사가 재요청보다 먼저다. 성공 기록을 다른 사용자에게 내주지 않는다.
     */
    @Transactional
    fun charge(userId: Long, @Valid request: PointChargeRequest): PointAccountInfo {
        checkUserExists(userId)
        val account = findAccount(userId)
        pointHistoryRepository.findByAccountIdAndChargeKey(account.id, request.chargeKey)?.let { first ->
            if (first.amount != Money(request.amount)) {
                throw CoreException(ErrorType.IDEMPOTENCY_KEY_CONFLICT)
            }
            return PointAccountInfo.replayOf(first)
        }

        val history = account.charge(Money(request.amount), chargeKey = request.chargeKey)
        pointHistoryRepository.save(history)
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
