package com.loopers.domain.user

/**
 * 사용자 저장 약속. 사용자는 실습용 데이터라 이 조각은 저장과 "있는가"만 묻는다.
 * 요청자 식별이 `existsById`에 기댄다(설계 5.27).
 */
interface UserRepository {
    fun save(user: User): User

    fun existsById(id: Long): Boolean
}
