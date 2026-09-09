# 계약 관찰 테스트의 도구 선택 기록 (Week 1)

| 항목 | 값 |
| --- | --- |
| 작성일 | 2026-09-09 |
| 대상 파일 | `apps/commerce-api/src/test/kotlin/com/loopers/interfaces/api/ContractClassificationTest.kt` |
| 관련 문서 | `docs/week1/order-discount-contract.md` 5장 (관찰 표) |
| 확인한 버전 | Spring Boot 3.4.4, Spring Framework 6.2.5, spring-test 6.2.5, Jackson 2.18.3, AssertJ 3.26.3 (모두 `testRuntimeClasspath` 에서 확인) |
| 제품 코드 변경 | 없음 |

이 문서는 `ContractClassificationTest` 를 쓰면서 내린 두 가지 도구 선택을 기록한다.

1. 응답 body 를 `ApiResponse` 로 역직렬화하지 않고 raw JSON 문자열로 받아 JsonPath 로 읽는다.
2. 기존 E2E 테스트와 과제 안내가 예시로 든 `TestRestTemplate` 대신 `RestClient` 를 쓴다.

둘 다 "더 좋은 도구라서" 고른 것이 아니다. 이 테스트는 **요청자가 wire 에서 실제로 보는 것**을 고정하려고 만든 테스트이고 그 목적에 맞는 도구를 고른 결과다. 값을 검증하는 일반 기능 테스트였다면 다른 선택을 했을 것이다.

## 1. 이 테스트의 목적이 도구를 정한다

`ContractClassificationTest` 는 새 기능을 검증하지 않는다. 이미 있는 `GET /api/v1/examples/{id}` 에 네 종류의 입력을 보내고 요청자가 보게 되는 외부 계약 네 가지를 고정한다.

| 관찰 항목 | 관찰 위치 |
| --- | --- |
| HTTP status | 응답 status line |
| `meta.result` | body |
| `meta.errorCode` | body (있을 때만) |
| `data` 유무 | body 에 키가 **존재하는지** |

마지막 항목에서 도구가 갈린다. 이 테스트는 "`data` 가 null 이다"가 아니라 "`data` 라는 키가 없다"를 말하고 싶다. 두 문장은 요청자에게 다른 계약이다. 이 차이를 관찰할 수 있는 도구만 후보가 된다.

## 2. 결정 1: 응답 body 를 raw JSON 으로 관찰한다

### 2.1 네 입력이 실제로 돌려주는 wire 모양

`JacksonConfig` 가 `serializationInclusion(NON_NULL)` 을 켜 두었으므로 서버는 null 필드를 아예 쓰지 않는다. 실제 응답은 다음과 같다. (`message` 문구는 이 테스트의 관찰 대상이 아니므로 생략했다.)

```json
// 존재하는 숫자 ID → 200
{"meta":{"result":"SUCCESS"},"data":{"id":1,"name":"예시 제목","description":"예시 설명"}}

// abc → 400, 존재하지 않는 숫자 ID → 404, 미매핑 URL → 404 (문자열만 다르고 모양은 같다)
{"meta":{"result":"FAIL","errorCode":"Not Found","message":"…"}}
```

성공 응답에는 `errorCode` 와 `message` 키가 없다. 실패 응답에는 `data` 키가 없다. null 이 아니라 **없다**.

`errorCode` 값도 눈여겨볼 점이다. `ErrorType.NOT_FOUND` 라는 enum 이름이 아니라 `HttpStatus.NOT_FOUND.reasonPhrase` 인 `"Not Found"` 가 나간다. 테스트 상수 `ERROR_CODE_NOT_FOUND = "Not Found"` 는 이 wire 값을 그대로 적은 것이다. 서버 내부 이름이 아니라 요청자가 보는 문자열을 고정한다는 원칙을 여기에도 똑같이 적용했다.

### 2.2 이유 A: `NON_NULL` 직렬화 때문에 "키 없음"과 "null"이 역직렬화 뒤 합쳐진다

`ApiResponse` 의 `data: T?` 와 `Metadata.errorCode: String?` 는 nullable 이다. Jackson 은 없는 키를 만나면 그 자리에 null 을 넣는다. 그래서 위 두 응답을 `ApiResponse` 로 역직렬화하면 이렇게 된다.

```kotlin
// 200
ApiResponse(meta = Metadata(SUCCESS, errorCode = null, message = null), data = …)
// 400 / 404
ApiResponse(meta = Metadata(FAIL, "Not Found", "…"), data = null)
```

문제는 서버가 `"data": null` 을 명시적으로 보냈어도 **정확히 같은 객체**가 나온다는 점이다.

| wire | `ApiResponse` 로 읽은 결과 | raw JSON 으로 읽은 결과 |
| --- | --- | --- |
| `{"meta":{…}}` (`data` 키 없음) | `data == null` | `doesNotHavePath("$.data")` 통과 |
| `{"meta":{…},"data":null}` | `data == null` | `doesNotHavePath("$.data")` **실패** |

타입으로 읽으면 두 wire 를 구분할 수 없다. 쓸 수 있는 가장 강한 assertion 이 `isNull()` 이고 그마저 두 wire 모두에서 통과한다. "키가 없다"는 계약을 테스트가 말할 길이 없어진다. 관찰 표(관련 문서 5.1)의 "data 없음"은 이 구분을 전제로 한 표현이다.

이 구분은 요청자에게 실제 계약이다.

- JavaScript 클라이언트는 `undefined` 와 `null` 을 다르게 다룬다. OpenAPI 스키마도 optional 필드와 nullable 필드를 다르게 적는다.
- 직렬화 설정이 `NON_NULL` 에서 `ALWAYS` 로 바뀌면 요청자에게는 계약 변경이지만 타입으로 읽는 테스트는 그 변경을 알아채지 못한다.

### 2.3 이유 B: 미매핑 URL 은 `T` 를 고를 수 없고 body 모양 자체가 관찰 대상이다

네 입력은 모두 같은 `get(path)` 로 보낸다. 역직렬화하려면 호출 전에 타입을 정해야 한다. 기존 E2E 테스트가 그렇게 한다.

```kotlin
object : ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>>() {}
```

미매핑 URL 에는 맞는 DTO 가 없다. 그리고 그 입력에서 확인하려는 것은 "body 가 `ApiResponse` 모양인가" 그 자체다. 지금은 `ApiControllerAdvice.handleNotFound` 가 `NoResourceFoundException` 을 잡아 `ApiResponse` 로 돌려준다. 그 handler 가 빠지면 Spring 기본 `/error` body 가 나간다.

```json
{"timestamp":"…","status":404,"error":"Not Found","path":"/api/v1/no-such-resource/1"}
```

이때 두 방식의 실패 모양이 다르다.

| 방식 | 실패 지점 | 실패 메시지 |
| --- | --- | --- |
| `ApiResponse` 역직렬화 | `RestClient` 내부, assertion 이전 | `meta` 가 non-null 프로퍼티라 Kotlin 모듈이 역직렬화 예외를 던진다. 테스트는 어떤 계약이 깨졌는지 말하지 못한다. |
| raw JSON + JsonPath | assertion 줄 | "`$.meta.result` 경로가 없다". 깨진 계약 항목이 그대로 드러난다. |

### 2.4 이유 C: 앱의 `ObjectMapper` 는 일부러 너그럽다. 계약 고정 테스트는 그 반대가 필요하다

`JacksonConfig` 의 역직렬화 설정을 보면 서버는 요청을 최대한 받아 주도록 되어 있다.

- `FAIL_ON_UNKNOWN_PROPERTIES` 끔
- `FAIL_ON_IGNORED_PROPERTIES` 끔
- `ACCEPT_EMPTY_STRING_AS_NULL_OBJECT`, `ACCEPT_SINGLE_VALUE_AS_ARRAY` 켬

여러 클라이언트의 요청을 받는 서버에는 맞는 설정이다. 계약이 바뀐 것을 잡아내야 하는 테스트에서는 오히려 방해가 된다. 누군가 wire 의 `errorCode` 를 `error_code` 로 바꾸면 타입으로 읽는 테스트는 모르는 키를 조용히 버리고 `errorCode = null` 을 만든다. 성공 케이스의 `assertThat(meta.errorCode).isNull()` 은 계속 통과한다. JsonPath 의 `extractingPath("$.meta.errorCode")` 는 그 자리에서 실패한다.

### 2.5 관찰을 보장하는 장치: `toEntity<String>()` 과 `JsonContent`

`String` 을 대상 타입으로 주면 Spring 은 Jackson converter 가 아니라 `StringHttpMessageConverter` 를 고른다. 읽는 쪽에서 Jackson 이 개입하지 않으므로 `response.body` 는 서버가 보낸 바이트를 문자열로 푼 것 그대로다. 테스트 KDoc 의 "관찰"은 이 보장을 가리킨다.

앱의 `ObjectMapper` 를 아예 안 쓰는 것은 아니다. 딱 한 곳에서 쓴다.

```kotlin
assertThat(json).extractingPath("$.data.id").convertTo(Long::class.java).isEqualTo(saved.id)
```

`HttpMessageContentConverter.of(MappingJackson2HttpMessageConverter(objectMapper))` 는 JsonPath 로 **이미 꺼낸 leaf 값**을 원하는 타입으로 바꿀 때만 쓴다. 이 시점에는 `$.data.id` 가 있다는 사실을 이미 확인한 뒤다. 구조는 raw JSON 으로, 값 비교는 앱과 같은 mapper 로 한다. 왕복 손실 없이 두 가지를 모두 얻는다.

### 2.6 기존 E2E 테스트와의 대비: 역할이 다르다

`ExampleV1ApiE2ETest` 는 `ApiResponse<ExampleV1Dto.ExampleResponse>` 로 역직렬화한다. 그 테스트는 알려진 endpoint 에서 **값**을 검증하므로 맞는 선택이다. 다만 그 테스트의 오류 케이스 두 개를 보면 status 만 검사하고 `meta.result`, `meta.errorCode`, `data` 유무는 검사하지 않는다. 타입을 정한 순간 "data 가 없다"를 표현할 수 없게 되었기 때문이다. `ContractClassificationTest` 는 바로 그 빈자리를 채우려고 만든 테스트다. 두 테스트는 같은 endpoint 를 다른 축에서 본다.

| 테스트 | 묻는 것 | 읽는 방식 |
| --- | --- | --- |
| `ExampleV1ApiE2ETest` | 저장한 값이 응답 값과 같은가 | 타입으로 역직렬화 |
| `ContractClassificationTest` | 요청자가 보는 모양이 무엇인가 | raw JSON + JsonPath |

### 2.7 버린 대안과 치른 비용

| 대안 | 버린 이유 |
| --- | --- |
| `ApiResponse<ExampleResponse>` 로 역직렬화 | 2.2, 2.3, 2.4 의 이유로 이 테스트가 말하려는 계약을 표현할 수 없다. |
| `ApiResponse<Any?>` 로 역직렬화 | `T` 를 고르는 문제는 사라지지만 2.2 에서 말한 "키 없음"과 null 이 합쳐지는 문제는 그대로다. |
| `objectMapper.readTree()` 로 `JsonNode` 를 얻어 직접 검사 | 키 유무는 구분된다. 대신 `has()` / `isNull()` / `path()` 조합으로 손수 assertion 을 짜야 하고 실패 메시지가 "expected true but was false" 수준이 된다. `JsonContent` + AssertJ 는 spring-test 6.2 가 제공하는 도구이고 `hasPath` / `doesNotHavePath` / `extractingPath` 가 실패 시 경로와 실제 문서를 함께 보여 준다. 헬퍼 코드를 새로 쓰지 않아도 된다. |

비용도 있다.

- JsonPath 문자열(`"$.meta.result"`)은 컴파일러가 검사하지 않는다. 오타는 실행해야 드러난다.
- `ApiResponse` 의 필드 이름이 바뀌면 이 테스트는 깨진다. 계약을 고정하는 테스트에서는 비용이 아니라 목적이다. 값 검증 테스트에서는 같은 성질이 비용이 된다. 그래서 한 모듈 안에 두 스타일이 공존한다.

## 3. 결정 2: `TestRestTemplate` 대신 `RestClient` 를 쓴다

### 3.1 먼저 바로잡는 전제: `TestRestTemplate` 은 deprecated 가 아니다

"deprecated 를 피하려고"는 이 결정의 근거가 아니다. 사실 관계를 classpath 의 jar 와 sources jar 에서 직접 확인했다.

| 확인 대상 | 확인한 사실 |
| --- | --- |
| Spring Boot 3.4.4 `TestRestTemplate` (이 프로젝트) | 클래스 자체에는 `@Deprecated` 가 없다. 중첩 클래스 `CustomHttpComponentsClientHttpRequestFactory` 의 생성자 하나만 `@Deprecated(since = "3.4.0", forRemoval = true)` 다. |
| Spring Boot 4.1.1 `TestRestTemplate` | 여전히 있고 클래스는 deprecated 가 아니다 (3.4.4 와 같이 중첩 생성자 하나만 4.1.0 부터 deprecated). 다만 모듈은 `spring-boot-resttestclient` 로, 패키지는 `org.springframework.boot.resttestclient` 로 옮겼다. 같은 모듈에 `@AutoConfigureRestTestClient` 가 함께 들어 있다. |
| Spring Framework 6.2.5 `RestTemplate` Javadoc | "As of 6.1, `RestClient` offers a more modern API for synchronous HTTP access. … `RestClient` is the focus for new higher-level features." `RestTemplate` 은 없어지지 않지만 새 기능의 중심이 아니다. |
| Spring Framework 7.0 `spring-test` | `org.springframework.test.web.servlet.client.RestTestClient` 가 새로 들어왔다. `RestClient` 와 같은 fluent 모양의 테스트 클라이언트다. |

`TestRestTemplate` 은 `RestTemplate` 을 감싼 테스트용 클래스다. `RestTemplate` 은 유지 보수 단계에 있고 `RestClient` 가 현재 방향이다. 여기까지는 배경일 뿐 결정의 이유는 아니다. 이유는 아래 세 가지다.

### 3.2 이유 A: 오류 허용이 호출 단위로 드러난다

`TestRestTemplate` 은 생성 시점에 `NoOpResponseErrorHandler` 를 전역으로 건다 (3.4.4 sources 152행). 그래서 4xx/5xx 가 와도 절대 예외를 던지지 않는다. 편리하지만 테스트 코드만 읽어서는 어느 호출이 오류를 기대하는지 알 수 없다.

`RestClient` 는 Spring 기본값대로 4xx/5xx 에서 예외를 던진다. 이 테스트는 그 기본값을 유지하고 오류 응답 자체가 관찰 대상인 `get()` 한 곳에서만 허용을 선언한다.

```kotlin
restClient.get().uri(path).retrieve()
    .onStatus(HttpStatusCode::isError) { _, _ -> }   // 이 호출에 한해 4xx/5xx 를 ResponseEntity 로 받는다
    .toEntity<String>()
```

"이 테스트는 오류 응답을 관찰한다"는 의도가 코드에 적힌다. 나중에 같은 클래스에 성공만 기대하는 호출을 추가하면 그 호출은 기본값대로 예외를 던져 준다.

### 3.3 이유 B: Kotlin 에서 타입 지정이 짧고 `String` 으로 받는 의도가 드러난다

spring-web 6.2.5 는 `RestClient` 용 Kotlin 확장 함수(`RestClientExtensionsKt`)를 제공한다. `toEntity<String>()` 은 reified 타입 인자 하나로 끝난다. `TestRestTemplate` 로 같은 일을 하려면 다음처럼 써야 한다.

```kotlin
testRestTemplate.exchange(url, HttpMethod.GET, HttpEntity<Any>(Unit), String::class.java)
```

`String` 을 넘기는 이유(2.5)가 코드 한 줄에 드러나는 쪽이 이 테스트의 KDoc 과 맞는다.

### 3.4 이유 C: 추가 의존성 없이 프로젝트가 옮겨갈 API 모양에 맞춘다

- `RestClient` 는 spring-web 에 들어 있다. `spring-boot-starter-web` 을 이미 쓰므로 새 의존성이 없다. 이번 주 제약(빌드·의존성 변경 금지)을 지킨다.
- Boot 3.4.4 의 `RestClientAutoConfiguration` 이 prototype 스코프 `RestClient.Builder` bean 을 만들고 `HttpMessageConvertersRestClientCustomizer` 로 앱과 같은 `HttpMessageConverters` 를 붙여 준다. 테스트는 이 bean 을 생성자로 주입받아 `baseUrl` 만 얹었다. 앱과 같은 `ObjectMapper` 가 자동으로 따라온다.
- Spring Framework 7 의 `RestTestClient` 는 `get().uri().exchange()` 로 이어지는 같은 fluent 모양이다. 이 테스트의 호출 형태는 그쪽으로 거의 그대로 옮길 수 있다. `exchange(url, method, entity, type)` 형태는 그렇지 않다.

### 3.5 치른 비용

| 비용 | 대응 |
| --- | --- |
| base URL 을 직접 만들어야 한다. `TestRestTemplate` 은 `RANDOM_PORT` 를 자동으로 붙여 준다. | `@LocalServerPort` 를 주입받아 `baseUrl("http://localhost:$port")` 한 줄로 해결했다. |
| 기존 `ExampleV1ApiE2ETest` 와 클라이언트가 다르다. | 기존 테스트는 이번 주 제약상 손대지 않는다. 두 테스트는 목적이 다르므로(2.6) 도구가 달라도 각자의 이유가 있다. 나중에 두 테스트를 `RestTestClient` 로 옮기는 시점에 통일하면 된다. |
| 과제 안내는 `TestRestTemplate` 을 예시로 들었다. | 안내가 요구한 것은 네 입력의 status / `meta.result` / errorCode / `data` 유무 관찰이다. 도구는 수단이고 관찰 값은 관련 문서 5.1 표와 같다. |

### 3.6 버린 대안

| 대안 | 버린 이유 |
| --- | --- |
| `TestRestTemplate` | 3.2 에서 말한 전역 오류 무시가 이 테스트의 의도를 코드에서 지운다. 3.3 의 boilerplate 와 3.4 의 방향도 이유다. |
| `MockMvc` / `MockMvcTester` | 서블릿 컨테이너 없이 `DispatcherServlet` 만 돈다. `ControllerAdvice` 와 message converter 는 타지만 Tomcat 의 error page 전달이나 컨테이너 수준 필터 같은 실제 서버 경로는 거치지 않는다. "요청자가 보는 것"을 고정하려면 `RANDOM_PORT` 의 실제 서버가 필요하다. |
| `WebTestClient` | `spring-webflux` 의존성이 필요하다. 이번 주 제약에 걸린다. |
| `RestTestClient` | Spring Framework 7 / Boot 4 에만 있다. 이 프로젝트는 Boot 3.4.4 다. |

## 4. 확인 기록

위 사실은 다음 위치에서 확인했다. 모두 Gradle 캐시에 내려받은 jar 와 sources jar 다.

| 사실 | 확인 위치 |
| --- | --- |
| 프로젝트 버전 | `gradle.properties` (`springBootVersion=3.4.4`), `./gradlew :apps:commerce-api:dependencies --configuration testRuntimeClasspath` |
| `NON_NULL` 직렬화, 너그러운 역직렬화 설정 | `supports/jackson/src/main/kotlin/com/loopers/config/jackson/JacksonConfig.kt` |
| 오류 응답은 `ApiResponse.fail(errorType.code, …)` 로 만든다. `code` 는 reason phrase 다 | `ApiControllerAdvice.failureResponse`, `ErrorType` |
| `TestRestTemplate` 3.4.4 의 `NoOpResponseErrorHandler`, deprecated 범위 | `spring-boot-test-3.4.4-sources.jar` 의 `TestRestTemplate.java` 152행, 1063~1066행 |
| `TestRestTemplate` 4.1.1 의 위치와 상태 | `spring-boot-resttestclient-4.1.1.jar`, 같은 버전 sources jar |
| `RestTemplate` Javadoc 의 `RestClient` 안내 | `spring-web-6.2.5-sources.jar` 의 `RestTemplate.java` 95~106행 |
| `RestClient.Builder` 자동 구성과 converter 연결 | `spring-boot-autoconfigure-3.4.4-sources.jar` 의 `RestClientAutoConfiguration.java` |
| Kotlin 확장 `toEntity` | `spring-web-6.2.5.jar` 의 `org/springframework/web/client/RestClientExtensionsKt.class` |
| `JsonContent`, `HttpMessageContentConverter` | `spring-test-6.2.5.jar` |
| `RestTestClient` | `spring-test-7.0.8.jar`, `spring-test-7.0.9.jar` |
