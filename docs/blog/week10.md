# 15 Factor에서 10주차 관측까지

## 들어가며

10주차 과제는 사고 하나를 일부러 만든다. 주문 10건 중 3건에서 PG는 결제를 승인했는데 그 응답이 돌아오는 길에 사라진다. 세 주문은 결과 미확인(`UNKNOWN`)으로 남고, 그동안 `/actuator/health`는 계속 `UP`이라고 답한다.

이 글은 세 부분이다. 먼저 Kevin Hoffman의 *Beyond the Twelve-Factor App*이 Logs와 Telemetry에 대해 말하는 원칙을 정리한다. 다음으로 Thomas Vitale의 *Cloud Native Spring in Action* 13장이 그 둘을 Spring으로 구현하며 주는 교훈을 정리한다. 마지막으로 두 책에서 배운 것을 과제에 어떻게 적용했는지 적는다. 원칙이 기법을 고르게 했고, 한 번은 이미 만든 구현을 뒤집게 했다.

## 원칙: 15 Factor의 Logs와 Telemetry

Hoffman은 Heroku의 12 factor를 다시 쓰고 셋을 더해 15개로 만들었다. 더한 셋은 API first, Telemetry, Authentication and authorization이다. 이 가운데 관측을 직접 다루는 것은 Logs(6)와 Telemetry(14)다.

### Logs: 로그는 이벤트의 흐름이다

- 로그는 파일이 아니라 시간순으로 나오는 이벤트의 흐름이다. 애플리케이션은 그것을 stdout과 stderr로 쓴다.
- 애플리케이션은 로그가 어디로 가고 어디에 보관되는지 모른다. 모으고, 찾고, 보관하는 일은 플랫폼과 도구의 몫이다.
- 이유는 규모다. 인스턴스가 늘고 줄고 사라지는 환경에서는 로그 파일이 어느 디스크에 있는지 알 수 없다. 애플리케이션이 보관을 모르면 인스턴스 수가 바뀌어도 고칠 것이 없다.

여기서 내가 읽은 것은 책임의 경계다. 애플리케이션에 남는 일은 하나다. 밖에서 찾을 수 있는 모양으로 쓰는 것이다.

### Telemetry: 손댈 수 없는 것을 신호로 안다

Hoffman은 우주 탐사선에 비유한다. 발사한 뒤에는 손댈 수 없고, 탐사선이 보내오는 신호로만 상태를 안다. 클라우드에 올린 애플리케이션도 같다. 디버거를 붙일 수 없으니 무엇을 내보낼지를 미리 정해야 한다. 그는 그 신호를 세 갈래로 나눈다.

| 갈래 | 답하는 질문 | 누가 만드는가 |
| --- | --- | --- |
| 성능 모니터링(APM) | 얼마나 빠르고 얼마나 실패하는가 | 프레임워크와 플랫폼 |
| 헬스와 시스템 이벤트 | 프로세스가 살아 있는가 | 프레임워크와 플랫폼 |
| 도메인 telemetry | 업무가 되고 있는가 | 애플리케이션만 만들 수 있다 |

앞의 두 갈래는 프레임워크와 플랫폼이 거의 대신 만든다. 셋째는 업무가 무엇인지 아는 코드만 만들 수 있다. 이번 사고는 그 셋째 갈래에서만 보인다. 응답을 잃은 요청은 timeout 세 건으로 APM에 잠깐 스치고, 헬스에는 흔적이 없다. "끝나지 않은 결제가 3건 있다"는 문장은 결제가 무엇인지 아는 코드만 쓸 수 있다.

### 두 factor를 받치는 넷

Logs와 Telemetry는 무엇을 내보낼지를 말한다. 그 신호를 어떻게 만들지는 다른 factor 넷이 정한다.

- **Stateless processes (12)와 Disposability (7).** 프로세스는 언제든 죽을 수 있고, 잃으면 안 되는 것은 backing service에 둔다. 메모리에 든 숫자는 믿을 수 있는 원본이 될 수 없다.
- **Concurrency (13).** 규모는 같은 프로세스를 여럿 띄워 늘린다. 그래서 인스턴스 하나가 내는 숫자는 전체의 한 조각이고, 합치는 일은 밖에서 한다.
- **Administrative processes (10).** Hoffman은 일회성 관리 스크립트를 경계한다. 관리 작업도 애플리케이션과 함께 버전을 매기고 테스트하는 코드여야 한다.

한 줄로 줄이면 이렇다. 신호는 프로세스가 싸게 흘려보내고, 진실은 DB에 두고, 고치는 길은 테스트된 코드로 둔다.

## 기법: 13장의 교훈

*Cloud Native Spring in Action*은 2장에서 15 factor를 소개하고, 13장에서 그중 Logs와 Telemetry를 Spring으로 구현한다. 절마다 신호 하나와 그 신호가 지나는 길을 다룬다.

| 13장의 절 | 신호 | 앱이 하는 일 | 밖에서 하는 일 | Hoffman의 갈래 |
| --- | --- | --- | --- | --- |
| 13.1 | 로그 | stdout으로 쓴다 | Fluent Bit이 모으고 Loki가 보관한다 | Logs |
| 13.2 | 헬스 | Actuator가 liveness와 readiness를 연다 | 플랫폼이 재시작하거나 트래픽을 끊는다 | 헬스 |
| 13.3 | 지표 | Micrometer로 기록하고 `/actuator/prometheus`로 연다 | Prometheus가 긁어 가고 Grafana가 그린다 | APM |
| 13.4 | trace | OpenTelemetry가 span을 만든다 | Tempo가 보관한다 | APM |

이 장에서 가져온 교훈은 다섯이다.

**앱은 출력하기만 한다.** 로그, 지표, trace는 밖에서 모으고 보관하고 합친다. Logs factor가 로그에 대고 한 말을 13장은 세 신호 모두에 적용한다.

**헬스는 플랫폼 probe에 하는 응답이다.** 헬스만 길이 다르다. 보관하는 곳 없이 플랫폼이 응답을 바로 읽고 조치한다. liveness가 실패하면 재시작하고, readiness가 실패하면 트래픽에서 뺀다. 그래서 헬스에 무엇을 넣을지는 "그 조치가 이 문제를 고치는가"로 정한다. 재시작해도 낫지 않는 문제를 liveness에 넣으면 멀쩡한 프로세스만 계속 죽는다.

**지표는 밖에서 긁어 간다.** Micrometer는 로깅의 SLF4J 같은 facade다. 코드는 counter, gauge, timer에 기록하고, registry가 그것을 Prometheus의 형식으로 바꾼다. Prometheus는 정해진 간격으로 `/actuator/prometheus`를 긁는다. 그래서 지표는 마지막 scrape만큼만 새롭고, 인스턴스마다 낸 값은 Prometheus에서 합친다.

**신호는 식별자로 이어진다.** 13.4절은 trace ID를 로그에 실어 trace 하나에서 그 요청의 로그로 건너간다. 로그가 쓸모 있으려면 한 건을 끝까지 따라갈 식별자가 실려 있어야 한다.

**자동 구성은 프레임워크가 아는 것만 잰다.** Actuator와 Micrometer를 붙이면 HTTP 요청 수와 지연, JVM 메모리, 커넥션 풀에 대한 상태를 추가적인 코드 없이 얻을 수 있다. `supports/monitoring`이 health와 prometheus를 별도의 management 포트에 열어 두었고, 10주차 전까지는 `MeterRegistry`를 부르는 코드는 한 줄도 없었다. 그러나 이렇게 라이브러리 모듈만 더한 구성은 결제 도메인에 대해서는 모른다. 추가적으로 필요한 것이 Hoffman의 셋째 갈래, 도메인 telemetry다.

다섯 가운데 가장 자신 없는 것은 분산 추적이다. 따로 해 본 Spring Boot 4의 OpenTelemetry 연동 튜토리얼 프로젝트에서 같은 요청 ID나 trace ID를 가진 span들을 한 화면에서 찾아보는 것까지는 해 봤다. 그러나 그 ID가 어떻게 만들어지고 서비스 사이를 어떻게 건너가는지, span이 어떻게 부모와 자식으로 묶이는지는 아직 설명하지 못한다. 이번 과제에서 분산 추적을 하지 않은 데에는 이 이유도 있다.

Claude에게 물어보니 다음을 공부해 보기를 추천했다.

1. **ID는 어떻게 서비스를 건너가는가.** W3C Trace Context의 `traceparent` 헤더를 읽는다. 버전, trace ID, 부모 span ID, flag의 네 필드로 된 한 줄이고, 다음 서비스가 같은 trace임을 아는 방법은 이것이 전부다.
2. **span은 어떻게 tree가 되는가.** span마다 자기 ID와 부모의 span ID를 적고, 보관하는 쪽이 그 쌍으로 tree를 다시 짠다. 튜토리얼 프로젝트의 두 서비스에서 `traceparent`를 로그로 찍어 보면 trace ID는 그대로이고 부모 ID만 바뀌는 것을 볼 수 있다.
3. **Kafka는 어떻게 건너가는가.** 같은 값이 HTTP 헤더 대신 record 헤더로 간다. 이 저장소에서는 여기가 설계 문제가 된다. 아웃박스의 중계는 행을 쓴 요청과 다른 시점, 다른 스레드에서 발행하므로 trace가 거기서 끊긴다. 주문 확정에서 streamer까지 이으려면 trace의 맥락을 아웃박스 행에 저장했다가 중계할 때 되살려야 한다.

지금은 이벤트에 실은 `orderId`와 `pgOrderId`가 trace ID가 할 일을 손으로 하고 있다. 이번 사고의 진단이 분산 추적 없이 된 것도 그래서다.

## 적용: 두 책에서 배운 것을 과제에

과제가 요구한 것은 둘이다. 결제의 경계에서 남기는 구조화된 로그, 그리고 끝나지 않은 결제의 수를 알리는 업무 지표 하나다. 13장의 기법은 그 둘을 싣는 길이고, 무엇을 실을지는 직접 정해야 했다. 배운 것과 한 일을 먼저 나란히 놓는다.

| 배운 것 | 출처 | 과제에서 한 일 |
| --- | --- | --- |
| 로그는 이벤트의 흐름이고 앱은 쓰기만 한다 | Logs, 13.1 | 결제 경계의 이벤트 아홉 가지를 key-value로 남겼다 |
| 도메인 telemetry는 앱만 만들 수 있다 | Telemetry | 끝나지 않은 결제의 gauge를 만들었다 |
| 인스턴스의 숫자는 조각이고 밖에서 합친다 | Concurrency, 13.3 | DB를 세던 gauge를 메모리의 수로 뒤집었다 |
| 헬스의 답에는 플랫폼의 조치가 따른다 | 13.2 | health indicator를 만들지 않았다 |
| 잃으면 안 되는 것은 backing service에 둔다 | Stateless processes, Disposability | 복구 대상은 gauge가 아니라 DB에서 읽었다 |
| 관리 작업은 테스트된 코드다 | Administrative processes | 이미 있는 대사 API로 복구했다 |

### 로그는 찾을 수 있는 이벤트로

`OrderPaymentSaga`가 PG의 답과 결제 반영의 결과마다 이벤트 하나를 남긴다. 아홉 가지이고, 고정된 문장 하나에 key-value가 붙는다.

```json
{
  "level": "WARN",
  "fields": {
    "event": "pg.create.outcome-unknown",
    "trigger": "confirm",
    "orderId": "13",
    "paymentId": "13",
    "pgOrderId": "7c45c7c0-e39a-4ee5-8451-1267831714c9",
    "cause": "NoHttpResponseException"
  }
}
```

Logs factor대로 앱은 쓰기만 한다. 대신 문장을 파싱하지 않고도 `orderId` 하나로 한 주문의 이벤트를 차례로 걸러낼 수 있게 썼다. 사용자 ID, 금액, 카드 정보는 싣지 않는다. 분산 추적(13.4)은 하지 않았고, 주문의 경로는 이 식별자들로만 잇는다.

### 지표는 DB를 세지 않는다

처음 만든 gauge는 10초마다 결제 테이블을 세었다. 동작했지만 질의 하나가 끌고 온 것이 많았다. 상태 컬럼의 인덱스, 예약 작업과 그 스위치, 첫 갱신 전의 `NaN`이 따라왔다. 인스턴스마다 DB 전체의 같은 수를 내니 `sum`이 아니라 `max`로 읽어야 한다는 규칙도 생겼다.

Telemetry와 Concurrency를 다시 읽고 하루 만에 뒤집었다. 지표는 프로세스가 본 것을 흘려보내는 것이고, 합치는 일은 Prometheus의 몫이다. 지금의 gauge는 결제 상태가 커밋으로 바뀔 때마다 메모리의 수를 올리고 내린다.

```kotlin
override fun statusChanged(from: PaymentStatus?, to: PaymentStatus) {
    if (from != null) {
        counts[from]?.decrementAndGet()
    }

    counts[to]?.incrementAndGet()
}
```

saga는 트랜잭션이 커밋하고 돌아온 뒤에 이 포트를 부른다. scrape는 두 수를 읽기만 하고 질의를 돌리지 않는다. 읽는 법은 다른 in-process 지표와 같은 `sum(commerce_payment_unresolved)`다. 질의, 인덱스, 예약 작업, 설정 둘은 모두 걷어냈다.

### 헬스는 건드리지 않는다

끝나지 않은 결제가 쌓이면 `DOWN`으로 답하는 health indicator는 만들지 않았다. 13.2절이 그 이유를 준다. readiness가 실패한 인스턴스는 트래픽에서 빠진다. 그러면 그 결제를 끝낼 대사 요청도 받지 못한다. 헬스는 플랫폼이 재시작과 라우팅을 정하려고 묻는 것이고, 업무의 경보는 지표에 둔다.

### 테스트는 실제 응답을 받는다

과제는 지표와 헬스를 실제 응답으로 확인하라고 한다. MockMvc에서는 management 서버가 뜨지 않는다. 그래서 실제 포트를 여는 테스트 기반을 새로 두었고, 테스트는 주입받은 management 포트에서 `/actuator/health`와 `/actuator/prometheus`를 받아 원문을 그대로 읽는다.

### 사고로 시험한다

사고는 `PaymentIncidentE2ETest`의 `@Test` 하나다. 기준 구간 10건, 장애 구간 10건, 진단, 복구, 후속 확인이 같은 DB와 같은 PG 거래 위에서 차례로 돈다. 단계마다 management 포트에서 받은 원문을 파일로 남긴다.

| 단계 | `/actuator/health` | `commerce_payment_unresolved{status="UNKNOWN"}` |
| --- | --- | --- |
| 기준 구간 뒤 | `UP` | 0 |
| 장애 구간 뒤 | `UP` | 3 |
| 복구 뒤 | `UP` | 0 |
| 후속 확인 뒤 | `UP` | 0 |

헬스는 네 번 모두 같은 답이고, 도메인 지표만 움직였다. Hoffman이 셋째 갈래를 따로 둔 이유가 이 표다.

#### gauge는 알리고, 로그가 가린다

gauge가 말하는 것은 "3건"까지다. 왜인지는 가설을 세워 증거로 가린다. 예상하는 관찰은 진단 전에 파일로 적어 둔다.

| 가설 | 예상한 관찰 | 판정 |
| --- | --- | --- |
| A. PG가 아직 처리 중이다 | PG 조회가 `PENDING` | 기각. 셋 모두 `SUCCESS` |
| B. 성공 응답을 잃었다 | 조회가 `SUCCESS`, 이벤트는 `pg.create.outcome-unknown` 하나뿐 | 채택 |
| C. 응답은 받았으나 반영에 실패했다 | `pg.create.accepted` 뒤에 `payment.apply.failed` | 기각. `pg.create.accepted`가 없다 |

B와 C는 PG 쪽에서 보면 똑같이 `SUCCESS`다. 둘을 가르는 것은 결제의 경계에서 남긴 이벤트의 차례뿐이다. 구조화된 로그가 없었다면 이 판정은 추측이었을 것이다. 진단은 읽기만 한다. PG에 조회를 보내고, 앞뒤로 커머스의 행이 그대로인지 확인한다.

#### 복구는 이미 있는 API로

복구 대상은 gauge가 아니라 DB에서 읽는다. 끝나지 않은 결제의 주문 ID를 읽어 장애 구간의 그 셋과 같은지 확인한 뒤, 5주차에 만든 대사 API를 주문마다 한 번 부른다.

```
POST /api/v1/orders/{orderId}/payment/reconcile
```

대사는 PG에 조회만 보내 찾은 `SUCCESS`를 반영한다. 결제 생성은 다시 보내지 않는다. 사고용 스크립트도 새 endpoint도 만들지 않았다. 사고 중에 처음 돌려 보는 길을 믿지 않으려는 것이고, Administrative processes factor가 말하는 것도 그것이다.

복구의 증명은 업무 결과로 한다.

- 장애 구간의 같은 10건이 모두 확정된 주문이고, 결과 미확인은 0건이다.
- PG 주문 번호마다 결제 생성은 1번이고, 복구 중에 늘지 않았다.
- 재고는 정확히 20 줄었고, 포인트 잔액과 미사용 쿠폰은 그대로다.
- 대사와 PG 알림을 다시 보내도 아무것도 바뀌지 않는다.

#### 복구는 랭킹에 닿아야 끝난다

결제가 `SUCCESS`가 된 것으로 복구가 끝나지는 않는다. 확정된 주문은 아웃박스를 거쳐 Kafka로 발행되고, commerce-streamer가 구매 활동으로 기록하고, 9주차의 배치가 주간 랭킹에 센다. 같은 `@Test`가 복구 뒤에 이 길을 끝까지 따라간다. commerce-streamer와 commerce-batch는 실제 bootJar로 띄운다.

일부러 한 번 더 흔든다. 중계가 복구한 주문 하나를 broker에 발행한 뒤, 발행됨으로 표시하기 전에 멈추게 한다. 다음 회차가 그 이벤트를 다시 발행한다.

| 센 것 | 수 |
| --- | --- |
| 토픽의 record | 21 (서로 다른 이벤트 ID는 20) |
| 아웃박스 행 | 20, 모두 `PUBLISHED` |
| 구매 활동 | 20, 주문마다 하나 |
| 주간 랭킹 | 게시됨, 1위 그 상품, 점수 20 |

record는 21건인데 구매 활동은 20건이다. 두 번 온 이벤트를 streamer가 한 번만 기록했다. 응답을 잃었던 세 주문도 나머지 17건과 똑같이 랭킹 점수에 들어갔다. 사고가 업무 결과에 남긴 차이는 확정 시각 하나다. 세 주문은 복구한 날에 확정되었다.

#### 절차는 runbook으로

테스트가 한 일을 사람이 따라 할 수 있게 `docs/runbook/week10.md`에 옮겼다. 증상, 진단 질의, 조치 전제, 복구, 중단 조건, 복구 확인, 재진입, 한계의 여덟 절이다. 명령마다 사고 테스트의 어느 단계가 그것을 돌렸는지 적었다.

runbook에는 DB에 쓰는 SQL이 없고 다시 결제하는 명령도 없다. 결제를 바꾸는 명령은 대사 API 하나다. 관리 작업을 테스트된 코드로 두라는 Administrative processes factor가 문서에서는 이런 모양이 된다.

### 원칙이 치르게 한 값

메모리의 gauge는 Stateless processes와 Disposability가 경고한 그대로 약하다. 프로세스가 다시 시작하면 0에서 시작하고, DB에서 채우지 않는다. 그래서 재시작은 `sum`을 그 인스턴스가 들고 있던 값만큼 옮긴다. 끝나지 않은 결제를 들고 있던 인스턴스가 다시 시작하면 `sum`은 그만큼 적게 센다. 시작을 보지 못한 결제를 끝낸 인스턴스의 값은 음수가 되는데, 그 인스턴스가 다시 시작하면 `sum`은 그만큼 많이 센다. 끝나지 않은 결제가 없어도 경보가 울릴 수 있다.

이 약점은 고치지 않고 역할을 나눴다. **gauge는 경보이고, 몇 건이며 어느 주문인지의 근거는 결제의 행이다.** 사고 테스트가 복구 대상을 DB에서 읽은 것도 그래서다. 잃으면 안 되는 것은 backing service에 둔다는 원칙을 지표에도 그대로 적용한 것이다.

남은 한계도 적어 둔다.

- gauge는 몇 건인지만 말하고 얼마나 오래됐는지는 말하지 않는다.
- 분산 추적이 없어 PG와 Kafka 너머까지는 잇지 못한다.
- 대사 API는 주문의 소유자만 부를 수 있다. 운영자는 고객의 식별자로 불러야 하고, 그것이 운영자였다는 기록은 남지 않는다.
- 원인을 가리려면 PG에 직접 조회해야 한다. 커머스는 PG의 답을 보여 주지 않는다.
- 사고 테스트는 한 꼴의 사고만 돌렸다. 응답을 잃었고 PG 거래는 모두 `SUCCESS`인 경우다.
- runbook의 명령이 테스트와 같은지 자동으로 확인하는 것은 없다.

## 마치며

헬스가 `UP`이라는 말은 프로세스가 살아 있다는 뜻이고, 업무가 끝나고 있다는 뜻은 아니다. Hoffman의 원칙은 그 차이에 이름을 붙였고, 13장의 기법은 신호를 실어 나를 길을 주었다. 실을 신호는 결제를 아는 코드가 직접 만들어야 했다. 이번 주에는 이벤트 아홉 가지와 gauge 하나였다.

신호를 만든 것으로 끝나지도 않았다. 복구의 증명은 gauge가 0으로 돌아온 것이 아니라 그 주문이 주간 랭킹에 세어진 것이었다. 관측은 업무 결과까지 이어져야 뜻이 있다.

## 참고

- Kevin Hoffman, *Beyond the Twelve-Factor App* (O'Reilly, 2016)
- Thomas Vitale, *Cloud Native Spring in Action* (Manning, 2022), 2장과 13장
- 설계 문서 [`docs/design/payment-incident.md`](../design/payment-incident.md)
- runbook [`docs/runbook/week10.md`](../runbook/week10.md)
- 증거 [`docs/evidence/week10/20261127T055722Z-c9dcca96/`](../evidence/week10/20261127T055722Z-c9dcca96/)
