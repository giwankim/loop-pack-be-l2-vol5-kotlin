---
status: accepted
date: 2026-10-20
---

# PG 포트는 PG 거래만 돌려주고, 나머지 결과는 결과마다 다른 예외로 던진다

[ADR 0022](./0022-only-the-pre-create-rejection-proves-no-pg-transaction.md) 규칙 4는 포트가 PG의 결과를 던지지 않고 값(`Accepted`, `Rejected`, `Unknown`과 `Found`, `Unavailable`)으로 돌려준다고 했다. 예외를 실패로 옮기는 catch가 생길 자리를 없애려는 것이었다. 3단계는 조회에 서킷 브레이커와 제한된 재시도를 씌운다. 회복 라이브러리는 예외를 중심으로 만들어졌다. Resilience4j는 기본값으로 모든 예외를 실패로 세고 다시 시도하며 fallback도 예외로 고른다. Spring Framework 7의 `@Retryable`은 예외에만 다시 시도한다([조사](../research/resilience-on-boot4.md) §3, §6). 값으로 돌려주면 결과 술어(`recordResult`, `retryOnResult`)를 따로 맞추고, 열린 브레이커의 `CallNotPermittedException`을 다시 값으로 옮겨야 한다. 이 저장소의 다른 층도 예외로 알리고(`CoreException`, `RuleViolationException`), Kotlin도 입출력의 오류는 예외를 기본으로 한다(Roman Elizarov, "Kotlin and Exceptions", 2020). 그래서 포트를 예외로 바꾼다.

1. **포트는 PG가 말한 PG 거래만 돌려준다.**
   - `createTransaction`은 받아들여진 생성의 `PgTransaction`을 돌려준다. 성공이 아니다. PG 상태는 그 PG 거래가 말한다.
   - `retrieveTransaction`은 `PgTransaction?`이고, PG가 그 키를 모르면(404) `null`이다. `listTransactions`는 `List<PgTransaction>`이고 비어 있을 수 있다.
   - `null`과 빈 목록은 PG 거래가 없다는 증거가 아니다(ADR 0022 규칙 3). 대사에는 `listOfNotNull(…)`이나 목록을 그대로 넘긴다.
2. **나머지는 결과마다 다른 예외다.**
   - `PgRejectedException(rejection)`: 정확한 생성 전 거절. PG 거래가 없다.
   - `PgOutcomeUnknownException`: 생성의 결과 미확인. 실패가 아니다.
   - `PgUnavailableException`: 조회 불가.
   - 세 예외는 `RuntimeException`을 바로 넓히고 공통 상위 타입이 없다. catch 하나로 거절과 결과 미확인을 함께 잡아 같게 다루는 길을 막는다.
   - 메시지는 HTTP 상태(`HTTP 503`)나 실패의 종류(`SocketTimeoutException`)다. 원인 예외를 잇지 않는다. 해석 오류의 메시지는 응답 본문의 조각을 담을 수 있다([결제 설계 3.5](../design/payments.md)).
3. **무엇이 어느 결과인지는 그대로다.** ADR 0022 규칙 2·3의 증거, `retrieve()` 대신 `exchange`로 상태 코드를 직접 읽는 것, 호출마다 `RestClientException`을 잡는 catch 하나는 바뀌지 않는다. 그 catch가 값 대신 포트의 예외를 던진다.
4. **호출자(2단계)는 PG 호출을 트랜잭션 안에 두지 않고, PG 예외를 넓게 잡지 않는다.**
   - 과제가 PG 호출을 트랜잭션 밖에 둔다. 예외에서는 그것이 더 무겁다. `PgOutcomeUnknownException`이 결제를 결과 미확인으로 저장한 트랜잭션(TX A)을 지나면 Spring이 그 트랜잭션을 되돌려, 생성을 보냈다는 기록이 사라진다. 같은 멱등 키로 다시 확정하면 결제가 없으니 생성이 다시 나간다.
   - `PgOutcomeUnknownException`과 `PgUnavailableException`은 저장된 상태를 돌려주는 것으로 끝낸다. 실패로 옮기지 않는다. `Exception`이나 `RuntimeException`을 잡는 catch로 PG 호출을 감싸지 않는다.
   - 2단계의 장면 테스트가 호출자마다, 생성이 결과 미확인일 때 결제가 결과 미확인으로 남는지 본다.
5. **포트 밖으로 나가는 PG의 결과는 세 예외뿐이다.** 열린 브레이커가 거절한 조회도 포트 밖에서는 `PgUnavailableException`이다. 3단계의 브레이커와 재시도는 어댑터 안에서 조회만 감싼다(ADR 0022 규칙 7). 어느 예외를 세고 다시 시도할지와 감싸는 차례는 3단계가 정한다.

## 고르지 않은 것

- **값으로 돌려준다(ADR 0022 규칙 4 그대로).** sealed 타입의 `when`이 호출자에게 모든 결과를 다루게 하고, 결과 미확인이 트랜잭션을 되돌리는 일도 없다. 그러나 브레이커와 재시도를 결과 술어로 맞추고, 라이브러리가 던지는 예외를 다시 값으로 옮겨야 한다. 이 저장소의 다른 층과 Kotlin의 입출력 기본값과도 다르다.
- **조회만 던지고 생성은 값으로 둔다.** 규칙 4의 위험, 결과 미확인을 실패로 옮기는 넓은 catch, 받아들여진 생성을 성공으로 읽는 것은 모두 생성 쪽에 있다. 브레이커는 생성을 감싸지 않는다(과제, ADR 0022 규칙 7). 그러나 포트 하나에 두 방식이 섞인다. 한 방식을 골랐다.
- **`kotlin.Result`.** 실패 쪽이 `Throwable`이라 결과가 결국 예외다. `runCatching`은 그대로 던져야 할 프로그래밍 오류까지 삼킨다.
- **공통 상위 예외(`PgException`).** 규칙 2의 까닭으로 두지 않는다.
- **조회 불가를 `null`로 돌려준다.** `null`이 404와 조회 불가를 함께 뜻하게 되어, 로그와 3단계의 브레이커가 "PG가 멈췄다"와 "아직 보이지 않는다"를 가르지 못한다.

## 대가

- Kotlin에는 검사 예외가 없어, 결과 미확인을 다루지 않은 호출자를 컴파일러가 잡지 못한다. 장면 테스트와 리뷰가 잡는다.
- 규칙 4의 트랜잭션 위험이 생긴다. 값이었다면 결과 미확인은 TX A를 되돌리지 않았다.
- 받아들여진 생성이 평범한 반환값이라 성공으로 읽히기 쉽다. KDoc과 `PgTransaction`의 PG 상태가 그렇지 않다고 말한다.
- 포트의 예외는 `exchange` 안에서도 던지므로, `http.client.requests` observation의 `exception`에 그 이름이 남는다. 422의 생성 전 거절과 202가 아닌 생성 응답, 200·404가 아닌 조회 응답이 그렇다. 받아들여진 생성과 찾은 조회만 오류가 없다.
- "아직 없음"이 거래 키 조회에서는 `null`, 목록 조회에서는 빈 목록으로 두 모양이다.

## 다시 볼 조건

- 2단계에서 PG 예외를 넓게 잡거나 PG 호출이 트랜잭션 안에 들어가는 실수가 장면 테스트에 걸리면, 생성만 값으로 되돌리는 것을 다시 견준다.
- Kotlin이 오류를 반환 타입에 담는 기능(제안된 rich errors)을 안정 기능으로 들이면 다시 견준다.
- 생성 앞에 브레이커를 두기로 하면, TX A 앞에서 묻고 그 거절은 결과 미확인과 다른 예외로 둔다. 보내지 않은 것이 확실하기 때문이다. 어댑터 안의 브레이커는 TX A 뒤에 돌아, 거절해도 결과 미확인인 결제가 남는다.

## 바꾸는 결정

[ADR 0022](./0022-only-the-pre-create-rejection-proves-no-pg-transaction.md) 규칙 4를 바꾼다. 그 ADR과 [ADR 0021](./0021-payment-is-its-own-aggregate-and-slice.md) 규칙 3에는 원문을 두고 날짜 메모를 단다. [결제 설계](../design/payments.md) 3.2, 3.3, 3.5가 이 ADR을 따른다.
