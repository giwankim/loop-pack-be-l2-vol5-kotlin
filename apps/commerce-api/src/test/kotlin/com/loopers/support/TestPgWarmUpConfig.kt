package com.loopers.support

import com.loopers.adapter.integration.pg.PgProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 테스트 PG 서버가 테스트의 요청을 받기 전에 관리 API에 한 번 물어 데운다. `@EnableTestPg`가 가져온다.
 *
 * test 프로필의 응답 한도는 200 ms다(`application.yml`). 테스트 JVM에서 처음 오가는 요청은 Jetty와 WireMock의 코드를 처음 읽고 돌리느라
 * 그 한도 안에서도 느리다. CPU가 바쁘면 200 ms를 넘겨, 성공으로 답한 생성이 어댑터에는 시간 초과로 보이고 결제가 결과 미확인으로 남았다.
 * 장면의 첫 생성이 그 값을 치르지 않도록, 컨텍스트가 다 뜬 뒤 다른 HTTP 클라이언트로 `/__admin/health`를 한 번 부른다.
 *
 * 관리 API의 요청은 WireMock의 요청 기록에 남지 않으므로 `createCount()`와 `unmatchedRequests()`가 그대로다. 어댑터의 HTTP 클라이언트를
 * 쓰지 않으므로 그 연결 풀과 observation도 건드리지 않는다. 데우는 요청이 실패하면 컨텍스트가 뜨지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestPgWarmUpConfig(
    private val pgProperties: PgProperties,
) {
    /** 서버 주소는 어댑터와 같은 설정에서 읽는다. wiremock-spring-boot가 컨텍스트를 만들 때 `pg.base-url`에 서버 주소를 넣는다. */
    @EventListener(ContextRefreshedEvent::class)
    fun warmUp() {
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI.create("${pgProperties.baseUrl}/__admin/health"))
                .timeout(WARM_UP_TIMEOUT)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())

            check(response.statusCode() == 200) { "테스트 PG가 데우는 요청에 ${response.statusCode()}로 답했다." }
        }
    }

    companion object {
        /** 바쁜 JVM에서도 처음 요청이 끝나도록 넉넉하다. 넘기면 컨텍스트가 뜨지 않는다. */
        private val WARM_UP_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
