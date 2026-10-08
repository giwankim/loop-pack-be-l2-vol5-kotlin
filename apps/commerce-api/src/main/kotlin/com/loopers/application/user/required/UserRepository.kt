package com.loopers.application.user.required

import com.loopers.domain.user.User
import org.springframework.data.repository.Repository

/**
 * 사용자 저장소. Spring Data가 구현을 만든다. 사용자는 실습용 데이터라 이 조각은 저장과 "있는가"만 묻는다.
 * 웹 경계의 요청자 식별이 `existsById`에 기댄다(ADR 0015).
 */
interface UserRepository : Repository<User, Long> {
    fun save(user: User): User

    fun existsById(id: Long): Boolean
}
