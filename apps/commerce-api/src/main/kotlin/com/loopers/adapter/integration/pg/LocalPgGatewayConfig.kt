package com.loopers.adapter.integration.pg

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.core.registry.EntryAddedEvent
import io.github.resilience4j.core.registry.EntryRemovedEvent
import io.github.resilience4j.core.registry.EntryReplacedEvent
import io.github.resilience4j.core.registry.RegistryEventConsumer
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory

private val log = KotlinLogging.logger {}

/**
 * 로컬 테스트 PG 어댑터의 HTTP 설정(ADR 0021, ADR 0022). PG 요청이 지나는 Apache HttpClient 5를 직접 만든다.
 * Boot의 HTTP client 설정(`spring.http.clients.*`)은 재시도와 풀 대기를 다루지 않는다.
 * 게이트웨이 [LocalPgGateway]는 컴포넌트 스캔으로 등록되고, Boot의 `RestClient.Builder`에 이 request factory를 얹는다.
 *
 * The state log of the circuit breaker around the gateway's lookups lives here too. The breaker itself is configured under
 * `resilience4j.*` in `application.yml` (ADR 0030).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgProperties::class)
class LocalPgGatewayConfig {
    /**
     * PG 요청이 지나는 request factory. 빈이라서 컨텍스트가 닫힐 때 HttpClient와 연결 풀도 닫힌다.
     *
     * - 숨은 재전송을 모두 끈다(ADR 0022 규칙 5). 기본값이면 429·503에 POST를 한 번 더 보내고, 307·308에는 본문까지 복사해 따라간다.
     * - 시간 한도는 factory가 아니라 이 HttpClient에 둔다. factory에 한도가 없으면 HttpClient의 기본 요청 설정이 그대로 쓰인다.
     *   연결 한도는 연결 풀의 `ConnectionConfig`가, 풀 대기와 응답 한도는 `RequestConfig`가 맡는다.
     * - PG의 경로는 하나뿐이라 풀의 전체 연결 수와 경로당 연결 수가 같다.
     */
    @Bean
    fun pgRequestFactory(properties: PgProperties): HttpComponentsClientHttpRequestFactory {
        val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(properties.maxConnections)
            .setMaxConnPerRoute(properties.maxConnections)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(properties.connectTimeout))
                    .build(),
            )
            .build()
        val httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.of(properties.connectionRequestTimeout))
                    .setResponseTimeout(Timeout.of(properties.responseTimeout))
                    .build(),
            )
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build()

        return HttpComponentsClientHttpRequestFactory(httpClient)
    }

    /**
     * Logs one WARN per state transition of the [LocalPgGateway.PG_LOOKUP] breaker, with its name and the old and new states
     * (ADR 0030 rule 7). Nobody watches the metrics yet, so this line is how an operator sees a PG outage begin and end. Calls
     * that leave the state alone log nothing.
     *
     * Resilience4j's Boot module hands every `RegistryEventConsumer` bean to the registry, which calls it for each breaker it
     * creates from the configuration. A replaced breaker is subscribed again, so the log survives a registry replace.
     */
    @Bean
    fun pgLookupStateTransitionLogger(): RegistryEventConsumer<CircuitBreaker> {
        return object : RegistryEventConsumer<CircuitBreaker> {
            override fun onEntryAddedEvent(entryAddedEvent: EntryAddedEvent<CircuitBreaker>) {
                logStateTransitions(entryAddedEvent.addedEntry)
            }

            override fun onEntryRemovedEvent(entryRemoveEvent: EntryRemovedEvent<CircuitBreaker>) {
            }

            override fun onEntryReplacedEvent(entryReplacedEvent: EntryReplacedEvent<CircuitBreaker>) {
                logStateTransitions(entryReplacedEvent.newEntry)
            }
        }
    }

    private fun logStateTransitions(circuitBreaker: CircuitBreaker) {
        if (circuitBreaker.name != LocalPgGateway.PG_LOOKUP) {
            return
        }

        circuitBreaker.eventPublisher.onStateTransition { event ->
            val transition = event.stateTransition
            log.warn {
                "PG 조회의 서킷 브레이커 상태가 바뀌었다. name=${event.circuitBreakerName}, " +
                    "from=${transition.fromState}, to=${transition.toState}"
            }
        }
    }
}
