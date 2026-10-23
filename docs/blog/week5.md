# 견고한 PG 호출: 타임아웃, 재시도, 서킷 브레이커

5주차 과제는 주문 확정의 포인트 결제를 로컬 테스트 PG의 카드 결제로 바꾸는 것이었다. 과제의 제목대로 응답을 잃은 결제는 실패가 아니다. PG가 PG 거래를 저장한 뒤 응답만 잃었을 수 있다. 세 단계로 나눠 만들었다. 1단계는 결제 애그리거트와 PG 어댑터, 2단계는 PG로 결제하는 주문 확정과 재고 예약, 3단계는 PG의 알림과 조회의 회복이다.

이 글은 그중 PG를 부르는 길에 둔 장치를 다룬다. 시간 한도를 두기로 하고 그에 맞는 HTTP client를 골랐다. PG 조회에만 재시도와 서킷 브레이커를 씌웠고, 벌크헤드는 두지 않았다. 마지막으로 그 숫자들을 예산과 견준다.

미리 밝혀 둘 것이 있다. 시간 한도는 예산에서 내려 정하지 않았다. 과제가 조회 한 번의 예로 든 1초를 따랐고, 1단계의 설계 인터뷰는 "3단계의 총 대기 예산은 이 값으로 계산한다"고 정했다. 예산을 세운 것은 3단계다. 그 값으로 조회 하나의 위 끝을 계산해 바깥의 기준과 견주었고, 재시도와 브레이커의 숫자 몇 개가 그 견줌에서 나왔다. 고객이 기다릴 시간의 예산은 끝내 정하지 않았다.

| 결정 | 고른 것 | 견준 것 | 까닭 |
| --- | --- | --- | --- |
| 시간 한도 | 풀 대기 200 ms, 연결 500 ms, 응답 1 s | 기본값. JDK client에는 읽기 한도가 없고, HttpClient 5는 연결과 풀 대기를 3분까지 기다린다 | 죽거나 느린 PG가 요청 스레드를 붙잡지 않게 한다 |
| HTTP client | Apache HttpClient 5, 숨은 재전송을 모두 끔 | JDK `HttpClient` | 풀 크기, 풀 대기, 재전송의 스위치를 client마다 가진다 |
| 재시도 | PG 조회만, 시도 2번, 100 ms 고정 | 생성도 다시 보냄, 기본값(시도 3번, 500 ms) | 생성을 다시 보내면 이중 결제가 될 수 있다. 다시 시도는 순간의 실패를 위한 것이다 |
| 서킷 브레이커 | PG 조회만, 두 조회가 `pg-lookup` 하나를 나눠 씀 | 생성 앞의 브레이커, 기본값(창 100, 최소 호출 100) | 죽은 PG에 대사할 때마다 시간 한도를 다 치르지 않게 한다 |
| 벌크헤드 | 두지 않음 | Resilience4j의 Bulkhead | 연결 풀 20과 풀 대기 200 ms가 이미 동시 호출을 묶는다 |

## 먼저 정해 둔 것: 다시 보내도 되는 요청은 조회뿐이다

PG 계약은 셋이다. PG 거래를 만드는 생성(`POST /api/v1/payments`), 거래 키로 하는 조회, PG 주문 번호로 하는 조회다. 모든 요청은 `X-USER-ID: 135135`를 싣는다.

생성의 응답을 잃으면 커머스는 PG 거래가 생겼는지 모른다. 시간 초과나 5xx를 실패로 보고 예약을 돌려주면, PG가 저장해 둔 PG 거래가 나중에 성공했을 때 돈은 빠져나가고 재고는 없다. 고객이 다시 결제하면 두 번 낸다. 그래서 PG 거래가 없다는 증거는 하나만 인정했다. PG가 "만들지 않았고 앞으로도 만들지 않는다"고 명시하는 생성 전 거절이다. 422, 정확한 `code`, boolean `false`인 `transactionCreated`를 모두 확인해야 한다. 나머지는 모두 결과 미확인이다([ADR 0022](../adr/0022-only-the-pre-create-rejection-proves-no-pg-transaction.md)).

결과 미확인인 결제에 생성을 다시 보내지도 않는다. 확정은 TX A에서 결제를 결과 미확인으로 저장하고 재고를 예약한 뒤 commit한다. 그 뒤 트랜잭션 밖에서 생성을 한 번 보내고, 답은 TX B가 반영한다([ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md)). 테스트 PG는 같은 PG 주문 번호의 생성을 하나로 합친다고 약속하지 않는다. 결과는 조회와 대사로만 안다.

이 두 규칙이 이 글의 나머지를 정했다.

- 다시 보내도 되는 요청은 조회뿐이다. 재시도와 브레이커도 조회에만 붙는다.
- PG 호출은 트랜잭션 밖에 있다. PG를 기다리는 동안 쥐는 것은 트랜잭션도 행 잠금도 아니고, 요청 스레드와 PG 연결 하나다. 아래의 예산이 시간으로 재는 것이 그것이다.

## 시간 한도를 두고 HTTP client를 골랐다

commerce-api에는 밖으로 나가는 HTTP 호출이 하나도 없었고, 시간 한도도 없었다. `RestClient.builder()`가 고르는 JDK client에는 읽기 한도가 없다. Apache HttpClient 5는 연결과 풀 대기를 각각 3분까지 기다린다. 죽은 PG를 부르는 요청은 그동안 요청 스레드를 쥔다.

조사는 JDK `HttpClient`를 먼저 꼽았다. 새 의존성이 없고, HTTP/1.1에서는 POST를 스스로 다시 보내지 않는다. Spring 7.0.9의 JDK factory는 읽기 한도를 교환 전체의 기한으로 건다. [RestClient 시간 한도와 숨은 재시도](../research/rest-client-timeouts-and-http-fixtures.md) 연결 풀을 쓰고 싶어 Apache HttpClient 5를 함께 견주자 답이 바뀌었다.

| 무엇 | JDK `HttpClient` | Apache HttpClient 5 |
| --- | --- | --- |
| 동시 연결과 풀 대기의 한도 | 없다 | 풀의 전체·경로당 연결 수와 `connectionRequestTimeout` |
| 숨은 재전송 | 응답 전에 닫힌 연결에 GET을 한 번 더 보내고, 끌 수 없다. 재시도 플래그는 JVM 전체의 시스템 속성이다 | 기본값으로 429·503에 POST를 1초 뒤 한 번 더 보내고, `IOException`에 GET을 한 번 더 보내고, 307·308을 본문째 따라간다. client마다 끌 수 있다 |
| 응답 한도 | 교환 전체의 기한 | 읽기마다의 한도 |

느린 PG에 요청이 쌓이지 않게 하려면 동시에 PG로 가는 요청의 수와, 연결을 얻으려고 줄에서 기다리는 시간을 함께 묶어야 한다. JDK client는 둘 다 못 한다. GET의 재전송을 끌 수 없다는 것도 걸렸다. 3단계의 테스트는 PG가 받은 조회 수를 세야 하는데, 끊긴 GET 하나가 2로 세어진다. 그래서 HttpClient 5를 골랐다.

대가는 기본값이 재전송한다는 것이다. `disableAutomaticRetries()`와 `disableRedirectHandling()`을 켜고, 게이트웨이의 계약 테스트가 재전송의 경로마다 PG가 받은 요청 수를 센다. 503에 POST 1번, 307에 POST 1번, 끊긴 GET에 GET 1번이다. 두 스위치를 하나씩 지우자 429·503에 POST가 2번, 끊긴 GET이 2번 갔고, 307은 따라갔다. 교환 전체의 기한도 내주었다. 1초가 되기 전마다 조금씩 보내는 PG는 응답 한도를 넘겨 호출을 붙잡을 수 있다. 이것은 받아들인 한계로 적었다.

시간 한도는 실제 request factory가 쓰는 HttpClient에 두었다. 연결 한도는 연결 풀의 `ConnectionConfig`가, 풀 대기와 응답 한도는 `RequestConfig`가 맡는다.

```kotlin
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
```

Boot의 HTTP client 설정(`spring.http.clients.*`)은 재시도와 풀 대기를 다루지 않으므로 쓰지 않았다. 대신 Boot가 주는 `RestClient.Builder`의 request factory를 이 HttpClient의 것으로 바꿨다. 바꾸지 않으면 클래스 경로에 따라 client가 달라진다. 테스트 클래스 경로에는 WireMock이 HttpClient 5와 Jetty client를 들이므로, 운영과 테스트가 서로 다른 client로 돌 수 있었다.

| 설정(`pg.*`) | 값 | 기본값 |
| --- | --- | --- |
| `max-connections` | 20(전체와 경로당) | 전체 25, 경로당 5 |
| `connection-request-timeout` | 200 ms | 3분 |
| `connect-timeout` | 500 ms | 3분 |
| `response-timeout` | 1 s, test 프로필은 200 ms | |

값의 근거는 짧다. 1 s는 과제가 조회 한 번의 예로 든 값이다. 연결 500 ms와 풀 대기 200 ms는 3분을 둘 수 없어 정했고, 따로 근거를 적지 않았다. PG의 경로는 하나뿐이라 풀의 전체와 경로당을 같게 두었다. 경로당 기본값 5를 그대로 두었다면 동시에 PG로 가는 요청이 다섯으로 묶였을 것이다.

test 프로필은 응답 한도를 200 ms로 줄여 시간 초과 장면이 스위트를 늦추지 않게 했다. 그런데 게이트웨이의 계약 테스트는 운영의 1 s로 돈다. HttpClient 5는 응답 한도가 재시도 간격 1초보다 짧으면 상태 코드의 재시도를 스스로 건너뛴다. 탐침에서 재시도를 켠 client는 503에 응답 한도 200 ms에서 POST를 1번, 2 s에서 1,025 ms 뒤 2번 보냈다. 200 ms로 돌리면 `disableAutomaticRetries()`를 지워도 "503에 POST 1번"이 통과한다. 설정을 지키는 테스트는 그 설정이 힘을 쓰는 조건에서 돌아야 한다.

## 재시도와 서킷 브레이커를 들인 까닭

2단계가 끝났을 때 조회에는 1단계의 시간 한도만 있었다. 조회는 고객의 대사(`POST /api/v1/orders/{orderId}/payment/reconcile`)와 3단계의 알림(`POST /api/v1/payments/callback`)이 부른다. 두 가지가 비어 있었다.

- **순간의 실패가 곧바로 조회 불가가 된다.** PG가 먼저 닫은 유휴 연결이나 한 번의 503도 그렇다. 고객은 저장된 상태를 받고, 알림은 503을 받아 PG의 재전송을 기다린다. 한 번 더 물었으면 끝났을 일이다.
- **죽은 PG에 묻는 대사는 매번 시간 한도를 다 치른다.** 요청마다 응답 한도까지 요청 스레드를 쥐고, PG에 요청을 보내는 것을 멈출 길이 없다. 알림이 쏟아지면 그 수만큼 쥔다.

앞의 것은 재시도가, 뒤의 것은 서킷 브레이커가 맡는다. 둘 다 조회에만 씌웠다.

생성에는 어느 쪽도 두지 않았다. 재시도는 앞에서 금했다. 브레이커는 금한 것은 아니지만 둘 자리가 맞지 않는다. 열린 브레이커가 생성을 거절하면 PG에 아무것도 가지 않은 것이 확실하다. 그러나 그 거절은 TX A가 결제를 저장하기 전에 나야 "보내지 않았다"는 증거가 된다. 저장한 뒤라면 보내지 않은 결제가 결과 미확인으로 남는다. 그러려면 TX A 앞에서 브레이커에 묻고, 그 거절을 결과 미확인과 다른 예외로 두어야 한다([ADR 0023](../adr/0023-pg-port-returns-transactions-and-throws-the-rest.md)의 다시 볼 조건). 과제도 브레이커를 조회 한 경로에 두라고 했다. 확정은 1단계의 시간 한도만 가진다.

대체 응답은 결과를 정하지 않는다. 조회할 수 없거나 브레이커가 열려 있으면 고객의 대사는 저장된 상태로 답하고(결제 중이면 202), 알림은 503 `PG_UNAVAILABLE`로 답해 PG가 다시 보내게 한다. 어느 쪽도 결제를 실패로 끝내지 않고 예약을 돌려주지 않는다.

## 라이브러리와 감싸는 차례

후보는 넷이었다.

| 길 | 까닭 |
| --- | --- |
| 코드로 감싼다: `Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, call))` | 차례가 코드에 보이고 프록시의 함정이 없다. 조사가 권한 길이다. 그러나 설정, registry, 지표, 헬스 지표를 손으로 엮어야 한다 |
| **`resilience4j-spring-boot4` 2.4.0의 애노테이션(고름)** | Boot 모듈이 그것들을 준다. 애노테이션의 함정은 테스트가 잡는다 |
| Framework 7의 `RetryTemplate`과 Resilience4j의 브레이커 | 두 어휘(`maxRetries`와 `maxAttempts`)와 두 설정 방식이 섞인다 |
| Spring Cloud Circuit Breaker | Resilience4j 2.3.0과 boot3 모듈을 쓰고, 기본값으로 호출을 다른 스레드로 옮겨 1 s의 TimeLimiter를 건다 |

Boot 4.1의 BOM에는 Resilience4j가 없고, `resilience4j-bom` 2.4.0은 boot4 모듈을 빠뜨린다. 그래서 버전 카탈로그에 판을 고정했다. AOP 스타터도 직접 들였다. Resilience4j 문서의 `spring-boot-starter-aop`는 Boot 4.0에서 `spring-boot-starter-aspectj`로 이름이 바뀌었다. AspectJ가 없으면 애노테이션은 debug 로그 한 줄만 남기고 아무것도 하지 않는다. [Boot 4의 회복 라이브러리](../research/resilience-on-boot4.md)

```kotlin
@Retry(name = PG_LOOKUP, fallbackMethod = "rejectedByOpenBreaker")
@CircuitBreaker(name = PG_LOOKUP)
override fun retrieveTransactionByPgOrderId(pgOrderId: String): PgTransaction? {
```

두 조회가 브레이커 하나를 나눠 쓴다. 같은 PG 호스트에 묻는 읽기라 함께 멈춘다. 브레이커가 둘이면 한쪽이 포기한 PG를 다른 쪽이 계속 부른다.

재시도가 브레이커를 감싼다. Resilience4j의 기본 차례 `Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(호출)))))` 그대로다. 그러면 HTTP 시도 하나가 브레이커의 표본 하나가 되어, 브레이커가 PG가 실제로 받은 요청만큼 PG를 잰다. 반대로 브레이커가 재시도를 감싸면 조회 하나가 표본 하나라 설명은 쉽다. 그러나 aspect의 차례를 기본값과 반대로 바꿔야 하고, 죽은 PG에 실제로 보낸 요청보다 표본이 적다.

무엇을 실패로 셀지는 예외로 정했다. 포트가 PG의 답이 아닌 것을 모두 `PgUnavailableException`으로 던지기 때문이다.

| 조회의 결과 | 브레이커 | 재시도 |
| --- | --- | --- |
| PG 거래 하나, 또는 없음(거래 키 조회의 404, 빈 배열) | 성공 | 하지 않는다 |
| PG 거래 여럿(`MultiplePgTransactionsException`) | 무시한다 | 하지 않는다 |
| 조회 불가(`PgUnavailableException`): 응답 시간 초과, 끊긴 연결, 연결 시간 초과, 풀 대기 초과, 예상하지 않은 상태 코드, 읽을 수 없는 본문 | 실패 | 100 ms 뒤 한 번 |
| 열린 브레이커의 거절(`CallNotPermittedException`) | 표본이 아니다 | 하지 않는다 |

- "없음"과 "여럿"은 PG가 답한 것이다. 실패로 세면 PG가 멀쩡히 답하는 동안 브레이커가 열린다.
- 느린 호출의 규칙은 두지 않았다. 응답 한도가 느린 호출을 이미 조회 불가로 만든다.
- PG의 400처럼 다시 해도 같은 답도 실패로 세고 다시 시도한다. 조회는 다시 해도 안전하고, 상태 코드를 가르는 것보다 단순하다.
- 열린 브레이커의 거절은 `@Retry`의 fallback이 `PgUnavailableException("circuit open")`으로 바꾼다. 그래서 Resilience4j의 타입은 어댑터 밖으로 나가지 않는다. fallback을 `@CircuitBreaker`에 두면 그 fallback이 재시도 안에서 돌아, 바꾼 예외를 재시도가 열린 브레이커에 다시 시도한다.

애노테이션은 조용히 꺼진다. AspectJ가 없거나, 같은 클래스 안에서 부르거나, 메서드가 열려 있지 않으면 그렇다. ADR은 게이트웨이가 AOP 프록시인지와 PG가 받은 요청 수를 함께 보라고 했는데, 해 보니 앞의 것만으로는 아무것도 증명하지 못했다. 게이트웨이는 `@Validated` 때문에 이미 CGLIB 프록시였다. 애노테이션이 걸리지 않은 빨간 실행에서도 `AopUtils.isAopProxy`는 통과했고, 조회는 1번에 결제는 처리 중으로 남았다. 꺼진 애노테이션을 잡은 것은 PG가 받은 조회 수 2였다.

## 벌크헤드를 아직 두지 않은 까닭

3단계 명세는 TimeLimiter, Bulkhead, RateLimiter를 범위 밖에 두었다. TimeLimiter를 두지 않는 까닭은 조사가 적었지만, Bulkhead의 까닭은 어디에도 적지 않았다. 다시 따져 보면 이렇다.

벌크헤드는 느린 의존성 하나가 스레드를 모두 가져가지 못하게 동시 호출의 수를 묶는다. PG 호출에는 그 한도가 이미 있다. 톰캣의 작업 스레드는 200개이고 PG의 연결 풀은 20개다. PG의 I/O에 묶이는 요청 스레드는 한 번에 많아야 20개다. 21번째 요청은 연결을 200 ms 기다린 뒤 포기한다. 생성이면 결과 미확인, 조회면 조회 불가다. Resilience4j의 세마포어 벌크헤드가 하는 일(`maxConcurrentCalls`, `maxWaitDuration`)과 같은 모양이고, 브레이커가 없는 생성도 이 한도 안에 든다. 풀 대기 초과도 조회 불가라 브레이커의 실패로 센다. 느린 PG에 조회가 몰리면 브레이커가 그만큼 빨리 열린다.

그 위에 Resilience4j의 Bulkhead를 더하면 한도가 둘이 되고, 두 숫자를 맞춰 두어야 한다. 기본 차례에서 벌크헤드는 가장 안쪽이라 그 거절(`BulkheadFullException`)이 브레이커와 재시도를 지난다. 지금 설정이라면 브레이커는 그것을 성공으로 센다. 실패로 셀 예외를 목록으로 정했기 때문이다. 재시도는 다시 시도하지 않고, fallback에도 맞지 않아 그 타입이 포트 밖으로 샌다. 실패의 표에 자리를 새로 정해야 한다. 스레드 풀 벌크헤드는 호출을 다른 스레드로 옮기므로 TimeLimiter와 같은 문제를 들인다.

TimeLimiter는 조사의 까닭 그대로다. TimeLimiter는 `Future`나 `CompletionStage`만 감싼다. 동기 `RestClient`에 쓰려면 호출을 다른 스레드로 옮겨야 하고, 시간이 다 되어도 기다리기를 그만둘 뿐 막힌 HTTP 호출은 그 스레드에서 계속 돈다. 동기 호출의 한도는 HTTP client의 시간 한도다.

풀이 막지 못하는 것도 있다. 생성과 조회가 같은 풀을 나눠 쓴다. 알림이나 대사가 많아져 조회가 연결을 차지하면, 확정의 생성이 풀 대기 200 ms 뒤 결과 미확인으로 끝나 예약을 쥔 채 남는다. 그때는 조회에 따로 한도를 둔다. 조회만 감싸는 벌크헤드를 두거나 조회의 풀을 나누는 것이다. 지금은 조회가 결제 중인 결제의 대사와 알림에서만 나와 드물다.

## 숫자를 정한 차례

### 지켜야 하는 바깥의 숫자

| 누구 | 예산 | 어디서 왔나 |
| --- | --- | --- |
| PG의 알림 | 10초 안에 200을 받아야 받은 것으로 본다. 아니면 1, 4, 16, 64…분 간격으로 최대 7번 다시 보낸다 | Toss의 문서 [웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events). 테스트 PG는 알림을 보내지 않아 실제 PG의 규칙을 빌렸다 |
| 커머스 전체 | PG가 멈춰도 상품 조회, 주문 생성, 관리자 기능은 돈다 | 이 저장소의 결정([ADR 0030](../adr/0030-pg-lookups-retry-once-inside-an-annotated-circuit-breaker.md) 규칙 7) |
| 고객의 대사와 확정 | 정하지 않았다 | |

### 조회 하나의 위 끝

1단계의 값에서 시작한다. 조회 하나는 시도 둘과 그 사이의 기다림 하나다.

| 경우 | 운영(응답 한도 1 s) | test 프로필(응답 한도 200 ms) |
| --- | --- | --- |
| PG가 연결을 받고 답하지 않는다 | 2 × 1 s + 100 ms = 2.1 s | 2 × 200 ms + 100 ms = 0.5 s |
| 위 끝. 시도마다 풀 대기, 연결 한도, 응답 한도를 다 쓴다 | 2 × (200 ms + 500 ms + 1 s) + 100 ms = 3.5 s | 2 × (200 ms + 500 ms + 200 ms) + 100 ms = 1.9 s |
| 브레이커가 열려 있다 | PG에 묻지 않고 바로 답한다 | 같다 |

확정의 생성은 다시 보내지 않으므로 위 끝이 200 ms + 500 ms + 1 s = 1.7 s다.

3.5 s는 알림의 10초 안에 들고, 결제를 찾고 TX B로 반영할 시간이 남는다.

### 다시 시도: 2번, 100 ms 고정

다시 시도는 순간의 실패를 위한 것이다. 끊긴 유휴 연결은 새 연결로 곧바로 다시 보내면 되고, 오래 기다려 얻는 것이 없다. 그래서 간격을 기본값 500 ms에서 100 ms로 줄였고, 간격을 늘려 가지도 않았다. 계속 과부하인 PG는 기다려 줄 일이 아니라 브레이커가 멈출 일이다.

시도를 기본값대로 셋 두면 위 끝은 3 × 1.7 s + 2 × 100 ms = 5.3 s다. 이것도 10초 안이다. 그러니 2를 고른 것은 예산이 아니다. 두 시도가 모두 실패했다면 이미 순간의 실패가 아니라는 판단이다. 예산이 한 일은 셋째 시도가 위 끝을 1.8 s 늘린다는 것을 보여 준 것이다.

### 브레이커의 창: 시도 10개, 최소 4개

이 경로의 호출은 드물다. 결제 중인 결제의 대사와 알림뿐이다. 기본값(창 100, 최소 호출 100)이면 판정까지 몇 시간이 걸릴 수 있어 사실상 열리지 않는다. 창은 시간이 아니라 호출의 수로 센다(`COUNT_BASED`). 시간 창은 테스트에서 결정적이지 않다.

최소 호출 4는 시도 2번 × 조회 2번이다. 죽은 PG에 대사를 두 번 하면 시도 넷이 모두 실패해 브레이커가 열리고, 셋째 대사는 PG에 요청을 보내지 않는다. 첫 대사의 두 시도는 최소 호출에 못 미쳐 판정하지 않는다. 최소 호출은 HALF_OPEN의 탐색 수 이상이어야 한다. Resilience4j는 탐색 수와 최소 호출 수 가운데 작은 쪽만큼 호출한 뒤 HALF_OPEN을 판정하므로, 최소 호출이 작으면 탐색을 다 하기 전에 판정이 난다. 실패율의 문턱은 기본값 50 %다.

### OPEN의 기다림: 60 s

기다림이 짧으면 기다림이 끝날 때마다 고객 요청 하나가 죽은 PG의 시간 한도를 치르고, PG는 요청을 둘 받는다. 길면 그동안의 대사와 알림이 PG에 묻지 않는다. 그 비용을 알림의 예산으로 쟀다.

열린 동안 알림은 503을 받는다. 다시 보내는 PG라면 그 알림을 잃지 않는다. Toss는 200이 아닌 알림을 1분 뒤 처음 다시 보낸다. 기다림이 1분보다 길면 첫 재전송도 열린 브레이커를 만나 다시 503을 받고, 다음 재전송은 그로부터 4분 뒤다. 60 s는 그 1분을 넘지 않는다. 남는 비용은 PG가 돌아온 뒤 최대 60 s 동안 고객의 대사가 저장된 상태로 답하는 것이다. 고객이 할 일은 PG 거래가 없을 때와 같다. 나중에 다시 묻는다.

10 s도 후보였다. 근거 없이 고른 값이었고, 위의 따짐은 기본값 60 s를 바꿀 까닭을 찾지 못했다.

자동 전이는 기본값대로 껐다. 기다린 뒤의 첫 호출이 HALF_OPEN으로 옮긴다. 옮기는 스케줄러 스레드가 없으므로 테스트는 시계만 옮기면 된다.

### HALF_OPEN의 탐색: 2

조회 하나가 시도 둘이다. 그래서 탐색을 시도 둘로 두면, 두 시도가 모두 실패한 조회 하나가 브레이커를 다시 열고 성공한 조회 둘이 닫는다.

### 헬스는 상태만 알린다

`allow-health-indicator-to-fail: false`다. 열린 브레이커는 DOWN이 아니라 `CIRCUIT_OPEN`을 알리고, Boot의 상태 집계가 자기 차례에 없는 상태를 빼므로 전체 헬스는 UP이다. 헬스가 DOWN이면 PG와 상관없는 상품 조회까지 커머스 API 전체가 아픈 것으로 보인다. 브레이커의 상태는 헬스 그룹 `/actuator/health/pg-lookup`과, 상태가 바뀔 때마다 남는 WARN 한 줄로 본다.

### 고객의 기다림은 정하지 않았다

3.5 s는 계산한 값이지 목표가 아니다. 3단계 명세의 사용자 이야기 24, 죽은 PG에 묻는 조회가 운영에서 고객의 요청을 3.5 s 남짓까지만 쥔다는 것도 이 계산에서 나왔다. 고객 쪽의 예산을 정한다면 거꾸로 거기서 시간 한도를 내려야 한다. 다만 응답 한도를 1 s 아래로 내려도 게이트웨이의 계약 테스트는 1 s에 남겨야 한다. 앞에서 본 HttpClient 5의 동작 때문이다.

## 숫자는 시간이 아니라 요청 수로 확인했다

테스트는 걸린 시간을 단언하지 않는다. PG가 받은 요청의 수와 브레이커의 상태, 이벤트를 본다. OPEN의 60 s는 테스트 시계로 넘는다. `CircuitBreakerConfigCustomizer`가 `pg-lookup`에 테스트 `Clock`을 넣고, 테스트가 그 시계를 옮긴다. 상태는 `transitionTo*`로 바꾸지 않고, 테스트 PG의 응답 유실이나 503 같은 실제 실패로 연다.

| 장면 | PG가 받은 조회 | 브레이커 | 결제 |
| --- | --- | --- | --- |
| 조회 불가 한 번 뒤 PG 거래를 찾는다 | 2 | | 같은 대사에서 확정 |
| 죽은 PG에 대사 둘 | 4 | OPEN | 결제 중 그대로 |
| 셋째 대사 | 4 그대로 | OPEN | 저장된 상태로 답한다 |
| 59 s 뒤 | 4 그대로 | OPEN | |
| 61 s 뒤, PG는 여전히 죽어 있다 | 6(탐색 둘) | 다시 OPEN | |
| 61 s 뒤 PG가 돌아오고, 결제 중인 두 주문을 대사한다 | 모두 6 | HALF_OPEN → CLOSED | 둘 다 확정 |
| 없음이나 여럿을 다섯 번 | 대사마다 1 | CLOSED | 결제 중 그대로 |

- 다시 시도의 기다림 100 ms는 재시도의 이벤트에서 읽는다. 처음에는 이 값을 보는 테스트가 없어 `wait-duration: 10s`도 통과했을 것이다. 명세 검토에서 찾아 더했고, 잠시 150 ms로 바꾸자 "expected: 0.1S but was: 0.15S"로 실패했다.
- 변이 실행. `@CircuitBreaker` 둘을 지우면 테스트 넷이 실패했다. `fallbackMethod`를 빼면 셋이 `CallNotPermittedException`이 포트 밖으로 새어 실패했다. 없음과 여럿의 장면은 두 변이 모두 통과한다. 그 장면은 브레이커가 있는지가 아니라 실패의 분류를 지킨다.

## 치른 값, 확인하지 않은 것, 다시 볼 조건

치른 값:

- 조회 하나가 요청 스레드를 최대 3.5 s 쥔다. 응답 한도는 읽기마다의 한도라, 조금씩 보내는 PG는 이를 넘길 수 있다. JDK client의 교환 전체 기한을 내준 값이다.
- 조회할 수 없는 조회는 PG에 GET을 2번 보낸다. 2단계에는 1번이었다.
- 브레이커 하나를 모든 고객과 알림이 나눠 쓴다. 죽은 PG에 대한 대사 둘이 60 s 동안 모두의 조회를 막고, PG가 돌아온 뒤에도 최대 60 s 동안 대사는 저장된 상태로 답한다.
- 고객의 대사는 조회 불가와 PG 거래 없음을 가르지 않는다. 둘 다 저장된 상태로 답하고, 조회 불가는 WARN과 헬스로만 드러난다.
- 애노테이션은 조용히 꺼질 수 있어, PG가 받은 요청 수를 세는 테스트에 기댄다. `resilience4j-spring-boot4` 2.4.0은 Boot 4.0.0과 Framework 7.0.2로 만들어졌고 Boot 4.1에 대해서는 말이 없다.
- 다시 시도의 100 ms는 테스트에서도 실제로 잔다. 조회할 수 없는 장면 하나에 0.5 s 남짓이 든다.

확인하지 않은 것:

- 고객이 기다릴 시간의 예산. 3.5 s는 계산한 값이다.
- 실제 PG의 응답 시간. 테스트 PG는 답하거나 멈추거나 끊을 뿐이라, 1 s가 정상 응답의 꼬리를 자르는지는 재지 않았다.
- 풀 20이 실제 동시 요청에 맞는지.
- HALF_OPEN에서 첫 시도가 실패하고 다시 시도가 성공한 조회. 결제는 끝나지만 탐색 둘의 실패율이 50 %로 문턱과 같다. Resilience4j는 문턱 이상이면 열므로 브레이커는 다시 열릴 것이다. 테스트하지 않았다.

다시 볼 조건:

- 고객 쪽 예산을 정하면 거기서 시간 한도를 다시 계산한다.
- 조회가 많아져 생성의 연결을 빼앗을 수 있게 되면 조회에 따로 한도를 둔다.
- 이 경로의 호출이 많아지면(주기적인 대사, 많은 알림) 창과 최소 호출 수를 늘린다.
- PG의 장애가 보통 60 s보다 훨씬 짧거나 길면 기다림을 바꾼다.
- 확정 앞에 브레이커가 필요해지면 TX A 앞에서 묻는다.
- PG가 같은 키의 생성을 하나로 합친다고 약속하면(Toss의 `Idempotency-Key`처럼), 생성의 재시도를 다시 견준다.

설계의 전체 기록은 [결제 설계](../design/payments.md)의 4절(클라이언트 설정)과 20절(조회의 회복), [ADR 0022](../adr/0022-only-the-pre-create-rejection-proves-no-pg-transaction.md), [ADR 0030](../adr/0030-pg-lookups-retry-once-inside-an-annotated-circuit-breaker.md), [ADR 0031](../adr/0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md)에 있다.
