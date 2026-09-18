package com.loopers.infrastructure.user

import com.loopers.domain.user.User
import org.springframework.data.jpa.repository.JpaRepository

/** [User]의 Spring Data JPA 저장소. [UserRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다. */
interface UserJpaRepository : JpaRepository<User, Long>
