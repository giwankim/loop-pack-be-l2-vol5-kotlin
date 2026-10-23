---
status: accepted
date: 2026-10-23
---

# PG 조회에만 재시도 한 번과 서킷 브레이커를 Resilience4j의 애노테이션으로 씌운다

과제 5절은 세 가지를 요구한다. PG 조회 한 경로에 서킷 브레이커를 두고 CLOSED → OPEN → HALF_OPEN → 회복 또는 재차단을 확인하며, 최소 표본·실패 조건·대기·탐색 호출 수를 정한다. 조회를 다시 할 때는 횟수·간격·총 대기 예산을 정하고 실제 호출 수를 확인하며, 생성은 다시 보내지 않는다. 조회가 실패하거나 회로가 끊기면 저장된 상태로 답하고, 결제를 실패로 끝내지 않는다.

자리는 이미 정했다. 재시도와 브레이커는 어댑터 안에서 조회 메서드만 감싸고([ADR 0022](./0022-only-the-pre-create-rejection-proves-no-pg-transaction.md) 규칙 7), 열린 브레이커가 거절한 조회도 포트 밖에서는 `PgUnavailableException`이다([ADR 0023](./0023-pg-port-returns-transactions-and-throws-the-rest.md) 규칙 5). ADR 0023은 "어느 예외를 세고 다시 시도할지와 감싸는 차례"를 3단계에 넘겼다. 이 ADR이 그것과 라이브러리, 설정값을 정한다. [조사](../research/resilience-on-boot4.md) §7은 코드로 감싸는 길(A)을 권했고, 이 ADR은 Boot 모듈의 애노테이션(B)을 고른다.

1. **`resilience4j-spring-boot4` 2.4.0과 `spring-boot-starter-aspectj`를 들인다.** Boot 4.1의 BOM에는 Resilience4j가 없고 `resilience4j-bom` 2.4.0은 boot4 모듈을 빠뜨리므로 버전 카탈로그에 판을 고정한다. Resilience4j 문서의 `spring-boot-starter-aop`는 Boot 4.0에서 `spring-boot-starter-aspectj`로 이름이 바뀌었다. 4.x 판은 4.0.0-M2에서 멈췄다. AspectJ는 지금 JPA를 거쳐서만 들어오는데, 없으면 애노테이션이 debug 로그 한 줄만 남기고 아무것도 하지 않으므로 직접 선언한다. actuator는 commerce-api에 이미 있다. 설정은 `application.yml`의 `resilience4j.*`에 둔다.
2. **`LocalPgGateway`의 두 조회 메서드에 `@Retry(name = "pg-lookup")`와 `@CircuitBreaker(name = "pg-lookup")`를 단다.** 거래 키 조회와 PG 주문 번호 조회가 브레이커 하나를 나눠 쓴다. 같은 PG 호스트에 묻는 읽기라 함께 멈추고, 브레이커가 둘이면 한쪽이 포기한 PG를 다른 쪽이 계속 부른다. 생성에는 어느 애노테이션도 달지 않는다. 같은 클래스 안에서 두 메서드를 부르지 않는다. 자기 호출은 aspect를 건너뛴다.
3. **재시도가 브레이커를 감싼다.** Resilience4j의 기본 차례(`Retry(CircuitBreaker(…))`)다. HTTP 시도 하나가 브레이커의 표본 하나라서, 브레이커가 PG에 실제로 보낸 요청만큼 PG를 잰다. 다시 시도하는 중에 브레이커가 열리면 다음 시도의 `CallNotPermittedException`을 다시 시도하지 않고(`ignore-exceptions`) 조회가 바로 끝난다.
4. **`PgUnavailableException`만 실패로 세고 다시 시도한다.** 시간 초과, 끊긴 연결, 연결 풀의 대기 초과, 예상하지 않은 상태 코드, 읽을 수 없는 본문이 모두 이 예외다. `null`(404, 빈 배열)은 PG가 답한 것이므로 성공이다. `MultiplePgTransactionsException`도 PG가 답한 것이라 브레이커가 무시하고(성공도 실패도 아니다) 다시 시도하지 않는다. 느린 호출의 규칙은 두지 않는다. 응답 한도가 느린 호출을 이미 조회 불가로 만든다. PG의 400처럼 다시 해도 같은 답도 세고 다시 시도하지만, 조회는 다시 해도 안전하고 상태 코드를 가르는 것보다 단순하다.
5. **열린 브레이커의 거절은 `@Retry`의 fallback이 `PgUnavailableException`으로 바꾼다.** fallback의 매개변수는 `CallNotPermittedException` 하나라서 다른 예외에는 맞지 않고 그대로 지나간다. `@CircuitBreaker`에는 fallback을 두지 않는다. 그 fallback은 재시도 안에서 돌아, 바꾼 `PgUnavailableException`을 재시도가 열린 브레이커에 다시 시도한다. Resilience4j의 타입은 어댑터 밖으로 나가지 않는다(ADR 0023 규칙 5).
6. **설정값은 아래 표다.** 기본값과 다른 줄만 까닭이 있다.

   | 설정 | 값 | 기본값 | 근거 |
   | --- | --- | --- | --- |
   | `sliding-window-type` | `COUNT_BASED` | 같다 | 시간 창은 테스트에서 결정적이지 않다. |
   | `sliding-window-size` | 10 | 100 | 이 경로의 호출은 결제 중인 결제의 대사와 알림뿐이라 드물다. 100이면 판정까지 몇 시간이 걸릴 수 있어 사실상 열리지 않는다. |
   | `minimum-number-of-calls` | 4 | 100 | 시도 2번 × 조회 2번이다. 죽은 PG에 대사를 두 번 하면 열리고, 세 번째 대사는 PG에 요청하지 않는다. 탐색 호출 수 이상이어야 HALF_OPEN의 판정이 탐색을 다 하기 전에 나지 않는다(조사 §3). |
   | `failure-rate-threshold` | 50 | 같다 | 다르게 둘 까닭이 없다. |
   | `wait-duration-in-open-state` | 60 s | 같다 | 짧으면 기다림이 끝날 때마다 고객 요청 하나가 죽은 PG의 시간 한도를 치르고, PG에 요청이 둘 간다. 길면 그동안의 대사와 알림이 PG에 묻지 않는다. 알림은 조회 불가에 503으로 답하므로 다시 보내는 PG에서는 잃지 않는다(Toss는 200이 아닌 알림을 1분 뒤 처음 다시 보낸다). 남는 값은 PG가 돌아온 뒤 최대 60 s 동안 대사가 저장된 상태로 답하는 것뿐이다. |
   | `permitted-number-of-calls-in-half-open-state` | 2 | 10 | 조회 하나가 시도 둘이다. HALF_OPEN에서 실패한 조회 하나(두 시도가 모두 실패)는 다시 열고, 성공한 조회 둘은 닫는다. |
   | `automatic-transition-from-open-to-half-open-enabled` | false | 같다 | 기다린 뒤의 첫 호출이 HALF_OPEN으로 옮긴다. 스케줄러 스레드가 없어 테스트는 시계만 옮기면 된다. |
   | 재시도 `max-attempts` | 2 | 3 | 첫 시도와 다시 시도 한 번이다. 셋이면 조회 하나의 위 끝이 5.4 s다. |
   | 재시도 `wait-duration` | 100 ms 고정 | 500 ms | 다시 시도는 끊긴 유휴 연결이나 한 번의 503 같은 순간의 실패를 위한 것이다. 다시 연결하는 데 오래 기다려 얻는 것이 없다. 계속 과부하인 PG는 브레이커가 맡는다. |

   예산은 조회 하나가 요청 스레드를 쥐는 시간이다. 위 끝은 2 × (연결 풀 대기 200 ms + 연결 500 ms + 응답 1 s) + 100 ms = 3.5 s다. PG가 연결을 받고 답하지 않는 흔한 경우는 2 × 1 s + 100 ms = 2.1 s, test 프로필(응답 200 ms)에서는 0.5 s다. 브레이커가 열려 있으면 PG에 요청하지 않는다.
7. **헬스 지표는 상태만 알린다.** `register-health-indicator: true`, `allow-health-indicator-to-fail: false`다. PG가 멈춰도 상품 조회, 주문 생성, 관리자 기능은 돈다. 그러니 커머스 API를 DOWN으로 만들지 않는다. 지표는 Boot 모듈이 들이는 `resilience4j-micrometer`가 낸다. 지표를 볼 화면이 없으므로 브레이커의 상태가 바뀔 때마다 registry의 이벤트 소비자가 WARN 한 줄을 남긴다.
8. **테스트는 실제 실패로 열고 시계로 기다림을 넘는다.** 테스트 PG의 조회 응답 유실이나 503으로 브레이커를 연다. `@TestConfiguration`의 `CircuitBreakerConfigCustomizer`가 넣은 테스트 `Clock`을 옮겨 OPEN의 기다림을 잠 없이 넘는다. `transitionTo*`는 부르지 않는다. 컨텍스트를 테스트 클래스끼리 나눠 쓰므로 테스트 기반 클래스가 `@BeforeEach`에서 registry로 `pg-lookup`을 되돌린다. 대기와 호출 수는 걸린 시간이 아니라 PG가 받은 요청 수와 재시도·브레이커의 이벤트로 확인한다. 한 테스트는 게이트웨이가 프록시인지(`AopUtils.isAopProxy`)와 PG가 받은 요청 수를 함께 본다. 애노테이션이 조용히 꺼지면 그 테스트가 실패한다.
9. **생성 앞에는 브레이커를 두지 않는다.** 과제가 조회 한 경로라고 정했고, 생성 앞의 브레이커는 TX A 앞에서 묻고 결과 미확인과 다른 예외를 새로 두어야 한다(ADR 0023의 다시 볼 조건). 확정은 1단계의 시간 한도만 가진다.
10. **대사의 응답은 바꾸지 않는다.** 조회 불가와 열린 브레이커도 지금처럼 저장된 상태로 답한다(결제 중인 주문이면 202). 과제의 "확인 불가"는 응답의 구분으로 나타나지 않는다. 결제와 주문이 그대로라는 것, 그리고 브레이커의 WARN과 상태로 나타난다. 고객이 할 일은 PG 거래가 없을 때와 같다. 나중에 다시 묻는다.

## 고르지 않은 것

- **코드로 감싼다(조사의 A).** `Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, call))`라 차례가 코드에 보이고 AspectJ도 프록시의 함정도 없다. 그러나 설정, registry, 지표, 헬스 지표를 손으로 엮어야 한다. Boot 모듈이 그것들을 주고, 애노테이션의 함정(AspectJ가 없음, 자기 호출, aspect의 차례)은 규칙 8의 테스트가 잡는다.
- **브레이커가 재시도를 감싼다.** 조회 하나가 표본 하나라 설명은 쉽다. 그러나 aspect의 차례를 기본값과 반대로 바꿔야 하고, 죽은 PG에 실제로 보낸 요청보다 표본이 적다.
- **Framework 7의 `RetryTemplate`에 Resilience4j의 브레이커.** 두 어휘(`maxRetries`와 `maxAttempts`)와 두 설정 방식이 섞인다.
- **Spring Cloud Circuit Breaker.** Spring Cloud 2025.1은 Resilience4j 2.3.0과 boot3 모듈을 쓰고, 기본값으로 호출을 다른 스레드로 옮겨 1 s의 TimeLimiter를 건다(조사 §1).
- **브레이커의 기본값 그대로.** 창 100과 최소 호출 100이면 이 경로에서는 열리지 않는다.
- **열린 브레이커의 기다림 10 s.** 근거 없이 고른 값이었다. 위 표의 따짐이 기본값을 바꿀 까닭을 찾지 못했다.
- **대사의 응답에 조회의 결과를 싣는다.** `lookup: UNAVAILABLE` 같은 필드는 `OrderConfirmer`에 결과 타입을 들이고, 503은 저장된 상태를 정상으로 돌려준 요청을 실패로 알린다. 고객이 할 일은 어느 쪽이든 같다.

## 대가

- 애노테이션은 AspectJ가 없거나, 자기 호출이거나, 메서드가 열려 있지 않으면 조용히 꺼진다. 규칙 8의 프록시와 요청 수 테스트에 기댄다.
- `resilience4j-spring-boot4` 2.4.0은 Boot 4.0.0과 Framework 7.0.2로 만들어졌고 Boot 4.1에 대해 말하지 않는다.
- 브레이커의 상태가 같은 컨텍스트를 쓰는 테스트끼리 이어지므로, 테스트 기반 클래스가 되돌려야 한다.
- 대사의 응답으로는 조회 불가와 PG 거래 없음을 가르지 못한다.
- 조회 하나가 요청 스레드를 최대 3.5 s 쥔다.
- 2단계의 조회 불가 장면은 PG가 받은 조회가 1에서 2가 된다. 찾음, 0건, 여럿의 장면은 다시 시도하지 않으므로 1 그대로다.

## 다시 볼 조건

- 이 경로의 호출이 많아지면(주기적인 대사, 많은 알림) 창과 최소 호출 수를 늘린다.
- PG의 장애가 보통 60 s보다 훨씬 짧거나 길면 기다림을 바꾼다.
- 확정 앞에 브레이커가 필요해지면 ADR 0023의 다시 볼 조건대로 TX A 앞에서 묻는다.
- Boot나 Resilience4j의 새 판에서 boot4 모듈이 맞지 않으면 코드로 감싸는 길(A)로 옮긴다. 설정값과 규칙 3–5는 그대로 옮겨 간다.

## 바꾸는 결정

ADR 0023 규칙 5가 3단계에 넘긴 "어느 예외를 세고 다시 시도할지와 감싸는 차례"를 이 ADR이 정한다. ADR 0022 규칙 7, ADR 0023 규칙 5와 [결제 설계](../design/payments.md) 17절의 날짜 메모는 그 코드를 바꾸는 작업이 단다.
