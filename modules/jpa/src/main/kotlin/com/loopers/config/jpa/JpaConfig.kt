package com.loopers.config.jpa

import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.annotation.EnableTransactionManagement

/**
 * 트랜잭션 advisor의 order는 `LOWEST_PRECEDENCE - 1`이다. 기본 order인 캐시 advisor보다 앞서므로 트랜잭션이 캐시를 감싼다.
 * commerce-api의 고객 상품 목록은 캐시 키에 넣을 revision을 그 트랜잭션의 첫 문장으로 읽는다(ADR 0020 규칙 2).
 * 캐시를 쓰지 않는 앱(commerce-streamer, commerce-batch)에는 아무것도 바뀌지 않는다.
 */
@Configuration
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
@EntityScan(basePackages = ["com.loopers"])
@EnableJpaRepositories(basePackages = ["com.loopers"])
class JpaConfig
