# runbook: health가 UP인 동안 끝나지 않은 결제가 남는다

10주차 사고의 runbook이다. 증상 하나만 다룬다. 결정의 출처는 [명세 286](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286)이고, 사고와 그 복구는 [결제 사고 설계](../design/payment-incident.md) 3절에 있다. 용어는 [`CONTEXT.md`](../../CONTEXT.md)를 따른다. 이 문서의 **운영자**는 용어집의 운영자다.

명령마다 그것을 돌린 `PaymentIncidentE2ETest`의 단계를 적었다. 단계는 그 `@Test`의 메서드다: `runBaseline`(기준 구간), `runFault`(응답을 잃는 구간), `runDiagnosis`(진단), `runRecovery`(복구), `runFollowOn`(중계, 구매 활동, 주간 랭킹). 테스트는 이 명령을 셸로 돌리지 않는다. 같은 질의와 같은 요청을 JDBC와 HTTP 클라이언트로 보낸다. 명령의 `<...>`는 placeholder이고, host와 자격 증명은 싣지 않는다.

이 runbook에는 DB에 쓰는 SQL이 없고 PG 거래를 만드는 명령이 없다. 결제를 바꾸는 명령은 고객의 대사 하나뿐이다(복구).

## 1. 증상

- **경보.** `sum(commerce_payment_unresolved)`가 0보다 크게 머문다. gauge의 `status`는 `UNKNOWN`(결과 미확인)과 `PENDING`(처리 중) 둘이다.
- **health는 `UP`이다.** 끝나지 않은 결제는 health를 바꾸지 않는다. 의도한 것이다([결제 사고 설계](../design/payment-incident.md) 2절). 그래서 health만 보는 감시는 이 사고를 알리지 않는다.
- 고객 쪽에서는 결제 중인 주문이 끝나지 않고, 그 주문의 재고 예약이 그대로 남는다.

인스턴스 하나의 값과 health는 이렇게 읽는다.

```sh
curl -s '<management 주소>/actuator/health'
curl -s '<management 주소>/actuator/prometheus' | grep '^commerce_payment_unresolved'
```

`runFault`가 돌렸다. 응답을 잃은 세 주문 뒤의 한 scrape에서 health는 `UP`이었고 gauge는 `UNKNOWN 3.0`, `PENDING 0.0`이었다. 진단을 뺀 단계마다 같은 관찰(`observe`)로 이 둘을 읽는다.

## 2. 진단 질의

**DB가 근거이고 gauge는 경보다.** gauge는 인스턴스마다 그 프로세스가 시작한 뒤로 본 것만 센다. 다시 시작한 인스턴스는 0에서 시작하므로 `sum`은 재시작 뒤 적게도 많이도 셀 수 있고, 끝나지 않은 결제가 없어도 0보다 클 수 있다. 인스턴스 하나의 값은 음수일 수 있다([결제 사고 설계](../design/payment-incident.md) 2.6절). 그래서 "몇 건이고 어느 주문인가"는 언제나 아래의 질의로 읽는다. 경보가 울렸는데 질의가 빈 목록이면 끝나지 않은 결제는 없다. 그 수는 재시작이 남긴 차이이고 이 runbook은 거기서 끝난다.

### 2.1 끝나지 않은 결제

```sql
select p.order_id, o.user_id, p.pg_order_id, p.amount, p.status, p.transaction_key, p.updated_at
from payment p
join orders o on o.id = p.order_id
where p.status in ('UNKNOWN', 'PENDING')
order by p.order_id;
```

- `user_id`는 그 주문의 소유자다. 복구의 대사가 그것을 싣는다(4절). `pg_order_id`는 PG 주문 번호이고, PG의 조회가 그것을 쓴다. 주문 ID와 다르다.
- `transaction_key`가 비었으면 커머스는 그 PG 거래를 모른다. 생성의 응답을 잃은 결제가 그렇다.
- `updated_at`은 결제 상태가 마지막으로 바뀐 때다. 결과 미확인의 결제라면 결제를 시작한 때다([결제 사고 설계](../design/payment-incident.md) 2.6절).

`runFault`와 `runRecovery`가 이 `where`와 `order by`로 끝나지 않은 결제의 주문 ID를 읽었다(`unresolvedOrderIds`). `runFault`에서는 응답을 잃은 세 주문과 같았고, `runRecovery`는 그것을 대상으로 고정했다. PG 주문 번호, 거래 키, 결제 상태는 같은 테스트가 `payment`에서 함께 읽는다(`readOrders`). `user_id`를 이 질의로 읽은 단계는 없다. 테스트는 소유자를 스스로 준비해 안다.

### 2.2 PG의 목록 조회

대상마다 PG에 직접 묻는다. 커머스에는 PG의 답을 보여 주는 endpoint가 없다.

```sh
curl -s -w '\nHTTP %{http_code}\n' -H 'X-USER-ID: <PG 계약의 사용자 ID(pg.user-id)>' '<PG 주소>/api/v1/payments?orderId=<PG 주문 번호>'
```

마지막 줄이 HTTP 상태다. 답은 PG 거래의 배열이고, 원소마다 `transactionKey`, `orderId`(PG 주문 번호), `amount`, `status`(PG 상태)를 싣는다. 읽기만 하는 조회다. PG 거래를 만들지 않는다.

`runDiagnosis`가 돌렸다(`sendListLookupToPg`). 세 대상 모두 답이 `SUCCESS` 하나였고, 조회 앞뒤로 그 주문의 `orders`, `payment`, `order_event_outbox` 행과 상품의 행이 같았다.

### 2.3 그 주문의 로그 이벤트

dev·qa·prd의 logstash JSON 로그에서 최상위 필드 `orderId`(또는 `pgOrderId`)로 걸러 시각의 차례로 읽는다. 필드와 이벤트의 목록은 [결제 사고 설계](../design/payment-incident.md) 2.3절이다. `test`와 `local`의 console은 문장만 찍고 key-value를 찍지 않으므로 거기서는 읽을 수 없다.

| 이벤트의 차례 | 읽는 것 |
| --- | --- |
| `pg.create.outcome-unknown` 하나뿐(WARN, `trigger=confirm`, `cause`) | 생성의 응답을 잃었다. PG 거래는 있을 수도 없을 수도 있고, 2.2절의 조회가 그것을 말한다 |
| `pg.create.accepted`나 `pg.lookup.found` 뒤의 `payment.apply.failed` | 받은 답을 반영하지 못했다. 이 runbook의 대사로 복구할 수 있지만, 반영이 왜 실패했는지 먼저 본다(3절의 첫 전제) |
| 마지막이 `pg.lookup.found`이고 `pgStatus=PENDING` | PG가 아직 처리 중이라 말한다. 5절의 중단 조건이다 |

`runDiagnosis`가 잡은 이벤트(`logEventsOf`)를 이렇게 읽었다. 세 대상 모두 `[pg.create.outcome-unknown]`이었고, 가설 B(성공 응답을 잃었다)를 채택했다. 반영 실패의 차례는 `OrderPaymentLogEventTest`가 단언한다.

## 3. 조치 전제

복구는 셋이 모두 참일 때만 시작한다.

1. **응답을 잃게 한 원인이 멈췄다.** 원인이 살아 있는 동안의 대사는 같은 일을 되풀이할 뿐이고, 새 확정이 새로 끝나지 않은 결제를 만든다. 무엇이 원인이었고 그것이 멈췄다는 것을 무엇으로 보았는지 적는다.
2. **대상의 목록을 DB에서 읽어 고정했다.** 2.1절의 질의를 돌리고 그 결과(주문 ID, `user_id`, PG 주문 번호, 금액)를 적어 둔다. 이 목록 밖의 주문은 이번 복구에서 건드리지 않는다. gauge의 수로 목록을 만들거나 채우지 않는다.
3. **PG의 조회가 대상마다 금액이 맞는 `SUCCESS` 하나를 보인다.** 2.2절의 답에 원소가 정확히 하나이고, 그 `orderId`가 대상의 PG 주문 번호, `amount`가 결제의 `amount`, `status`가 `SUCCESS`다. 결제가 거래 키를 알면 `transactionKey`도 같아야 한다.

`runRecovery`가 앞의 둘을 단언하고 시작했다. 대상을 DB에서 읽어 응답을 잃은 세 주문과 같다고 단언한 뒤 고정했고, 테스트 PG가 더는 응답을 잃지 않는다고 단언했다. 셋째는 절반만 단언했다. `runDiagnosis`는 조회의 PG 상태가 `SUCCESS` 하나라는 것만 단언하고 금액과 PG 주문 번호는 견주지 않았다. 그 둘이 맞다는 것은 `runRecovery`의 대사가 그 PG 거래를 충돌 없이 반영한 것에서 읽힌다.

## 4. 복구

대상마다 한 번, 고객의 대사를 소유자의 식별자로 부른다.

```sh
curl -s -w '\nHTTP %{http_code}\n' -X POST -H 'X-USER-ID: <대상의 user_id>' '<커머스 API 주소>/api/v1/orders/<주문 ID>/payment/reconcile'
```

- 마지막 줄이 HTTP 상태다. 기대하는 답은 200이고 `data.status`가 `CONFIRMED`, `data.paymentStatus`가 `SUCCESS`다.
- 대사는 PG 주문 번호로 PG를 조회하고, 찾은 PG 거래와 결제를 견주어 맞으면 결제 상태를 앞으로 옮긴다. PG 거래를 만들지 않는다.
- 200이 아니거나 `CONFIRMED`가 아니면 그 대상에서 멈추고 5절로 간다. 202는 주문이 아직 결제 중인 주문이라는 답이다. 대사가 PG를 조회할 수 없었거나, PG 거래를 찾지 못했거나 충돌만 얻었거나, PG가 아직 처리 중이라 말했다. 200이어도 `PAYMENT_FAILED`면 대사가 결제와 맞는 PG 거래의 실패를 확인한 것이다.
- `X-USER-ID`가 소유자가 아니면 404 `ORDER_NOT_FOUND`다. 대사는 요청자의 주문만 대사한다.
- 같은 주문의 확정을 새 멱등 키로 다시 보내지 않는다. 그것은 고칠 길이 아니다. 커머스는 생성을 보내기 전에 `IDEMPOTENCY_KEY_MISMATCH`로 거절한다([결제 사고 설계](../design/payment-incident.md) 3.10절).

`runRecovery`가 돌렸다. 세 대상을 소유자의 식별자로 한 번씩 대사했고 모두 200, `CONFIRMED`, `SUCCESS`였다. 대상마다 이벤트는 `pg.create.outcome-unknown` → `pg.lookup.found` → `payment.apply.committed`였고, 뒤의 둘은 `trigger=reconcile`, 마지막은 `result=changed`, `from=UNKNOWN`, `to=SUCCESS`였다.

## 5. 중단 조건

대상 하나에라도 아래 가운데 하나가 맞으면 운영자는 멈춘다. 그 대상에 대해 아무것도 바꾸지 않고 PG에 묻는다. 다른 대상의 복구를 이어 가도 되는지는 7절에서 목록을 다시 고정한 뒤 정한다.

1. **PG의 조회를 할 수 없다.** 2.2절의 `curl`이 연결되지 않거나 200이 아니다.
2. **PG 거래가 없거나 여럿이다.** 배열이 비었거나 원소가 둘 이상이다. 빈 배열은 PG 거래가 없다는 증거가 아니다. 생성 전 거절만이 그 증거다(용어집).
3. **금액이나 PG 주문 번호가 맞지 않는다.** 원소의 `amount`나 `orderId`가 결제와 다르다. 커머스의 대사는 그것을 충돌로 남기고 결제에 반영하지 않는다.
4. **PG가 `PENDING`이나 실패를 말한다.** PG 상태가 `SUCCESS`가 아니다.
5. **원인이 아직 살아 있다.** 3절의 첫 전제가 거짓이 되었다.
6. **DB의 끝나지 않은 결제가 고정한 목록과 다르다.** 2.1절을 다시 돌린 결과가, 이미 복구한 대상을 뺀 고정한 목록과 같지 않다. 새 끝나지 않은 결제가 생겼거나 다른 길(PG의 알림)이 이미 끝냈다.

**PG가 실패를 말해도 손으로 반영하지 않는다.** 결제를 실패로 끝내는 SQL도, 재고 예약을 돌려주는 SQL도 쓰지 않는다. 결제가 실패로 끝나는 것은 커머스의 대사가 결제와 맞는 PG 거래의 실패를 확인했을 때뿐이다. PG에 물어 그 PG 거래가 끝났다는 답을 받은 뒤, 7절에서 다시 시작한다.

이 경우들은 사고 테스트가 돌리지 않았다. 대사가 이런 답을 받으면 결제를 성공으로 바꾸지 않는다는 것은 5주차와 1단계의 장면이 단언한다([결제 사고 설계](../design/payment-incident.md) 3.6절).

## 6. 복구 확인

대상마다 주문과 결제에서 시작해 구매 활동까지 읽는다.

### 6.1 주문과 결제

```sql
select o.id, o.status, o.confirmed_at, p.status as payment_status, p.pg_order_id, p.transaction_key
from orders o
join payment p on p.order_id = o.id
where o.id in (<대상 주문 ID>, ...)
order by o.id;
```

- 주문은 `CONFIRMED`, 결제는 `SUCCESS`이고 `transaction_key`가 2.2절의 `transactionKey`와 같다. `confirmed_at`은 대사한 때다.
- 2.1절의 질의를 다시 돌려 대상이 하나도 남지 않았는지 본다.

`runRecovery`가 같은 행을 읽었다(`readOrders`). 열 건 모두 확정된 주문, 결제 성공, 예약 0이었고, 거래 키가 PG가 든 PG 거래의 것과 같았다. `confirmed_at`이 복구의 시각이라는 것은 `runFollowOn`이 단언했다.

### 6.2 생성이 늘지 않았다

- 2.2절의 조회를 다시 돌린다. 원소는 여전히 하나이고 `transactionKey`가 같다. 두 번째 PG 거래가 없다.
- 그 주문의 로그 이벤트(2.3절)에서 대사 뒤로 `pg.create.*`가 없다. 대사는 `pg.lookup.found`와 `payment.apply.committed`만 남긴다.

`runRecovery`는 PG가 받은 요청의 기록에서 생성 수를 셌다. PG 주문 번호마다 1이었고, 대사 앞뒤의 생성 수가 같았다. 운영자는 그 기록을 볼 수 없으므로 PG 거래의 수와 로그 이벤트로 대신 읽는다.

### 6.3 아웃박스

```sql
select order_id, event_id, status, attempt_count, published_at
from order_event_outbox
where order_id in (<대상 주문 ID>, ...) and deleted_at is null
order by order_id;
```

- 대상마다 행이 정확히 하나다. 확정한 트랜잭션이 남긴 것이다.
- 중계가 발행하면 `status`가 `PUBLISHED`가 된다. `PENDING`이면 중계가 아직 발행하지 않았거나 다시 시도할 차례를 기다린다. `BLOCKED`면 막힌 이벤트이고 이 runbook 밖이다([아웃박스 중계 설계](../design/order-outbox.md)).
- **행이 없으면 누락 복구다.** commerce-api의 인스턴스 하나를 그 주문 ID로 다시 시작한다. 더한 행은 중계가 발행한다. 시작 속성은 `order-events.recovery.order-ids`이고 쉼표로 가른다.

  ```sh
  java -jar <commerce-api의 bootJar> --order-events.recovery.order-ids=<주문 ID>,<주문 ID>
  ```

  누락 복구는 저장된 확정 사실에서 같은 이벤트 ID로 아웃박스의 이벤트를 더한다. 주문을 다시 확정하지 않고 PG 거래를 만들지 않는다. 사실이 모자라거나 어긋나면 거절하고, 거절은 시작을 실패시킨다. 다시 시작한 뒤에는 그 주문 ID를 속성에서 뺀다. 이미 행이 있는 주문을 지정하면 내용이 같은지 확인만 한다.

`runRecovery`와 `runFollowOn`이 20건의 주문마다 삭제되지 않은 행이 하나라고 단언했다(`outboxRowCounts`. 주문 ID로 거르지 않고 `deleted_at is null`의 행을 주문마다 센다). `runFollowOn`은 중계 뒤 20건이 모두 `PUBLISHED`라고 단언했다(`outboxRows`, `published_at`은 읽지 않는다). 사고 테스트에서는 행이 빠진 주문이 없어 누락 복구를 돌리지 않았다. 시작 속성은 7주차의 `OrderEventRecoveryRunnerTest`가 고정한다.

### 6.4 구매 활동

commerce-streamer의 표다.

```sql
select order_id, product_id, quantity, confirmed_at
from purchase_activity
where order_id in (<대상 주문 ID>, ...)
order by order_id, product_id;
```

- 주문의 품목마다 하나이고, `quantity`는 그 품목의 수량, `confirmed_at`은 6.1절의 주문의 `confirmed_at`이다.
- 같은 주문과 상품의 행이 둘이 아니다. 주문 확정 이벤트가 다시 발행되거나 다시 전달되어도 commerce-streamer는 같은 사실을 다시 기록하지 않는다. 토픽의 record 수와 구매 활동의 수는 다르게 셀 수 있으니 구매 활동은 이 표에서 센다.
- 행이 없으면 6.3절의 행이 `PUBLISHED`인지 먼저 본다. `PUBLISHED`인데 구매 활동이 없으면 commerce-streamer가 그 주문 확정 이벤트에서 멈췄을 수 있다. 이 runbook 밖이다([구매 활동 설계](../design/purchase-activity.md)).

`runFollowOn`이 같은 열을 같은 차례로 읽었다(`purchaseActivityRows`). 테스트는 주문 ID로 거르지 않고 표 전체를 읽으며, `confirmed_at`을 UTC의 글로 바꿔 읽는다. 20건의 주문마다 구매 활동 하나, 수량 1이었고, 복구한 세 주문의 `confirmed_at`은 복구의 시각이었다. 셋째로 복구한 주문의 주문 확정 이벤트를 두 번 발행해 토픽의 record는 21건이었지만, 구매 활동은 20건이었다. 그 주간 랭킹은 점수 20을 보였다.

## 7. 재진입

멈춘 뒤에는, 그리고 6.1절이나 6.2절이 대상 하나라도 끝나지 않았다고 말하면, 언제나 2.1절의 진단 질의부터 다시 시작한다. 결제가 끝난 주문은 2.1절의 목록에 다시 오지 않으므로, 6.3절이나 6.4절이 찾은 빈자리(아웃박스 행이나 구매 활동이 없다)는 재진입이 아니라 그 절이 적은 다음 걸음을 따른다.

1. 2.1절을 다시 돌려 대상의 목록을 새로 고정한다. 앞의 목록을 이어 쓰지 않는다.
2. 새 목록의 대상마다 2.2절과 2.3절, 3절의 전제를 다시 본다.
3. 아직 끝나지 않은 대상에만 4절을 되풀이한다.

이미 복구한 주문을 다시 대사해도 아무것도 바뀌지 않는다. `runRecovery`의 되풀이가 그것을 보였다. 복구 뒤 1초에 시계를 두고, 대상마다 두 번째 대사(200, `CONFIRMED`)와 PG의 알림을 다시 보냈다. 그 앞뒤로 20건의 주문, 결제, 상품, 아웃박스 행과 잡은 로그 이벤트, PG가 받은 요청의 수가 모두 같았다. 확정된 주문의 대사는 PG를 조회하지 않고 저장된 상태를 돌려준다. 다른 사용자의 대사는 404 `ORDER_NOT_FOUND`였다.

## 8. 한계

- **운영자는 고객으로서 대사하고, 그것이 운영자였다는 기록은 남지 않는다.** 대사 API는 요청자의 주문만 대사하므로 운영자는 소유자의 `X-USER-ID`를 싣는다. 로그 이벤트의 `trigger`는 고객의 대사와 같은 `reconcile`이다. 운영에서 그 헤더는 API 게이트웨이가 싣는 것이라, 운영자가 고객의 식별자를 어떻게 싣는지는 정하지 않았다([결제 사고 설계](../design/payment-incident.md) 3.8절).
- **진단하려면 PG에 직접 닿아야 한다.** 커머스는 PG의 답을 보여 주지 않는다. 운영자는 PG의 주소와 PG 계약의 사용자 ID를 알아야 하고, 로그의 key-value는 logstash JSON에만 있다.
- **이 글을 자동으로 확인하는 것은 없다.** 사고 테스트는 같은 질의와 요청을 제 코드로 돌린다. 이 문서의 명령이 그것과 같은지는 사람이 본다. 열이나 endpoint가 바뀌면 테스트는 따라 바뀌어도 이 글은 그대로일 수 있다.
- **사고 테스트는 한 꼴의 사고만 돌렸다.** 생성의 응답을 잃은 결과 미확인 세 건이고, PG 거래는 모두 `SUCCESS`였다. 5절의 경우들, 처리 중으로 남은 결제, 누락 복구는 사고 테스트에서 돌리지 않았다.
