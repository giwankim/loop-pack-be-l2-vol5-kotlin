package com.loopers.testcontainers

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 통합 테스트용 MySQL 컨테이너다. 컨테이너는 JVM마다 한 번 띄우고, 접속 정보는 이 설정을 가져온 컨텍스트의 `Environment`에만 넣는다.
 *
 * 최상위 `@TestConfiguration`은 컴포넌트 스캔에서 빠지므로, DB를 쓰는 테스트가 `@Import`로 명시해서 쓴다.
 * 시스템 프로퍼티와 달리 다른 컨텍스트로 새지 않으므로, `@Import`를 빠뜨린 테스트는 실행 순서와 상관없이 `${MYSQL_HOST}`를 풀지 못해 실패한다.
 *
 * 메타데이터 잠금을 기다리는 시간(`lock_wait_timeout`)은 10초로 줄인다. 테스트 트랜잭션이 쥔 메타데이터 잠금을 스키마 도구의
 * 다른 연결이 기다리면, 기본값 1년으로는 실패하지 않고 멈춘다. 행 잠금의 `innodb_lock_wait_timeout`은 서버 값을 그대로 두고,
 * 앱의 연결이 `jpa.yml`의 세션 변수로 3초를 쓴다(ADR 0018).
 */
@TestConfiguration(proxyBeanMethods = false)
class MySqlTestContainersConfig {
    companion object {
        private val mySqlContainer: MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .apply {
                withDatabaseName("loopers")
                withUsername("test")
                withPassword("test")
                withExposedPorts(3306)
                withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_general_ci",
                    "--lock-wait-timeout=10",
                )
                start()
            }
    }

    @Bean
    fun mySqlContainerProperties(): DynamicPropertyRegistrar {
        return DynamicPropertyRegistrar { registry ->
            registry.add("datasource.mysql-jpa.main.jdbc-url") {
                mySqlContainer.let { "jdbc:mysql://${it.host}:${it.firstMappedPort}/${it.databaseName}" }
            }
            registry.add("datasource.mysql-jpa.main.username") { mySqlContainer.username }
            registry.add("datasource.mysql-jpa.main.password") { mySqlContainer.password }
        }
    }
}
