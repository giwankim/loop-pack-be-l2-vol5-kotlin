package com.loopers.adapter.integration.pg

import com.loopers.application.payment.required.PaymentGateway
import io.micrometer.observation.ObservationRegistry
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.RestClient
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * 로컬 테스트 PG 어댑터(ADR 0021, ADR 0022). Boot의 HTTP client 스타터 없이 Apache HttpClient 5와 `RestClient`를 직접 만든다.
 * 스타터와 Boot의 HTTP client 설정은 재시도와 풀 대기를 다루지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgProperties::class)
class LocalPgGatewayConfig {
    companion object {
        /** PG 계약이 정한 합성 계정을 싣는 헤더. 모든 요청에 보낸다. */
        private const val USER_ID_HEADER = "X-USER-ID"
    }

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
     * 기본 헤더 `X-USER-ID`와 PG만의 매퍼를 단 `RestClient`로 게이트웨이를 만든다. observation registry가 있으면 다른 HTTP client 호출처럼
     * PG 호출도 기록한다. 어댑터의 설정만 담은 테스트 컨텍스트에는 registry가 없고, `ObservedPaymentGatewayTest`가 하나를 더해 기록을 본다.
     */
    @Bean
    fun localPgGateway(
        properties: PgProperties,
        pgRequestFactory: HttpComponentsClientHttpRequestFactory,
        observationRegistry: ObjectProvider<ObservationRegistry>,
    ): PaymentGateway {
        val builder = RestClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader(USER_ID_HEADER, properties.userId)
            .requestFactory(pgRequestFactory)
            .configureMessageConverters { it.withJsonConverter(JacksonJsonHttpMessageConverter(pgJsonMapper())) }
        observationRegistry.ifAvailable { builder.observationRegistry(it) }

        return LocalPgGateway(builder.build(), properties.callbackUrl)
    }

    /**
     * PG의 JSON만 읽고 쓰는 엄격한 매퍼. Boot의 매퍼(`supports/jackson`)는 우리 API를 부르는 클라이언트를 너그럽게 받는다.
     * 그 너그러움이 남의 계약을 읽는 데 새지 않게 따로 둔다. 컨버터를 직접 정하면 `RestClient`는 기본 컨버터를 더하지 않으므로 이 매퍼가 유일한 컨버터다.
     *
     * - 단일 값을 배열로, 빈 문자열을 null로 읽지 않는다.
     * - 원시 타입의 null과 빠진 값을 거절한다. null이 될 수 없는 Kotlin 타입은 Kotlin 모듈이 거절한다.
     * - 스칼라를 바꿔 읽지 않는다. `"7000"`은 숫자가, `"false"`는 boolean이 아니다. 금액은 원화 정수라 `7000.5`도 정수로 읽지 않는다.
     * - 모르는 속성은 무시한다. PG가 필드를 더해도 계약의 필드만 읽는다.
     */
    private fun pgJsonMapper(): JsonMapper {
        return jacksonMapperBuilder()
            .disable(
                DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT,
                DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            )
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build()
    }
}
