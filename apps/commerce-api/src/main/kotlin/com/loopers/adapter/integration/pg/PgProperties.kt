package com.loopers.adapter.integration.pg

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 로컬 테스트 PG 어댑터의 설정. 값은 `application.yml`의 `pg`에 있다.
 *
 * [callbackUrl]은 설정에서만 온다. 고객의 요청이 PG가 결과를 알릴 주소를 정하지 못한다.
 * [responseTimeout]은 읽기마다의 한도이고 호출 전체의 기한이 아니다.
 */
@ConfigurationProperties("pg")
data class PgProperties(
    val baseUrl: String,
    val callbackUrl: String,
    val userId: String,
    val maxConnections: Int,
    val connectionRequestTimeout: Duration,
    val connectTimeout: Duration,
    val responseTimeout: Duration,
)
