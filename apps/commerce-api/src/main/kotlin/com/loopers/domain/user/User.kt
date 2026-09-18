package com.loopers.domain.user

import com.loopers.domain.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 고객 기능을 쓰는 사람. 실습용 데이터라 이 조각에는 사용자를 만들거나 바꾸는 API가 없고, 뜻이 있는 것은 식별자뿐이다.
 * 요청자는 `X-USER-ID` 헤더가 실어 준 이 사용자의 식별자다(CONTEXT.md 요청자).
 *
 * 테이블 이름이 `users`인 까닭은 `user`가 SQL 표준의 예약어라서다(설계 5.27). 삭제 상태는 두지 않는다.
 */
@Entity
@Table(name = "users")
class User : BaseEntity()
