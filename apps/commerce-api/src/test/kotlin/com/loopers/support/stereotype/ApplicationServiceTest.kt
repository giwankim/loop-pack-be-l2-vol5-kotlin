package com.loopers.support.stereotype

import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

/**
 * provided 포트 테스트의 설정. 전체 컨텍스트를 실제 MySQL·Redis 위에 띄우고, 테스트 트랜잭션이 포트의 트랜잭션을 감싸
 * 테스트마다 롤백으로 정리한다. 추가 설정이 없어 [com.loopers.CommerceApiContextTest]와 컨텍스트를 나눠 쓴다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@SpringBootTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
@Transactional
annotation class ApplicationServiceTest
