package com.loopers.application.point.required

import com.loopers.domain.point.PointAccount
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.Repository

/**
 * 포인트 계정 저장소. Spring Data가 구현을 만든다. 계정은 사용자마다 하나라 사용자로 찾는다.
 * 없는 계정을 만들어 주지 않는다(설계 5.9).
 */
interface PointAccountRepository : Repository<PointAccount, Long> {
    fun save(account: PointAccount): PointAccount

    fun findByUserId(userId: Long): PointAccount?

    /**
     * [userId] 사용자의 계정을 `FOR UPDATE`로 잠가 읽는다. 없으면 null이다(ADR 0019).
     * `find`와 `By` 사이는 Spring Data가 설명으로 보므로 `@Query` 없이 [findByUserId]와 같은 파생 조회다.
     * `user_id`의 유일 인덱스를 따라 계정 행 하나만 잠그고, 기다린 뒤에는 가장 최근에 커밋된 잔액을 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByUserId(userId: Long): PointAccount?
}
