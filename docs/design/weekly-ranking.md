# 주간 랭킹 설계 — 랭킹 기간의 입력을 고정하고, 중단된 배치를 이어서 같은 랭킹을 만든다

9주차 과제의 설계 문서다. 용어는 주간 랭킹의 [`CONTEXT.md`](../../apps/commerce-batch/CONTEXT.md)("랭킹 기간", "스냅샷", "스냅샷 입력", "차수", "체크섬", "충돌", "고정하다", "게시하다")와, 구매 활동의 [`CONTEXT.md`](../../apps/commerce-streamer/CONTEXT.md)를 따른다. 결정의 출처는 1단계가 [명세 237](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237)이다. 1단계는 [ADR 0038](../adr/0038-weekly-ranking-tables-are-not-entities.md)을 둔다. READ COMMITTED와 인덱스는 ADR이 아니라 이 문서의 5.2절이 적는다.

상태: 2026-11-17 1단계(`feat/weekly-ranking-input`)를 인터뷰하고, 같은 날 그 일곱 티켓을 구현한 뒤 이 문서를 썼다.

## 1. 근거와 범위

- 과제: 9주차 실습 "중단된 배치를 이어서 같은 랭킹 만들기". 1단계의 몫은 2절(입력 고정)과 3절(job의 배선과 재시작 probe)이다.
- 6주차와 7주차로 확정된 주문의 구매 활동이 `purchase_activity`에 쌓인다. 주간 랭킹은 그것을 읽어 commerce-batch에서 만든다. 주문, 결제, 아웃박스, 구매 활동의 기록은 바뀌지 않는다. 스트리머에서 바뀐 것은 인덱스 한 줄이다(2절).
- 데모 job(`DemoJobConfig`, `DemoTasklet`, `DemoJobE2ETest`)은 그대로 둔다. `spring.batch.job.name=demoJob`일 때만 올라오므로 `weeklyRanking`의 실행에는 없다. 지우는 일은 [issue 236](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/236)이다.

### 1.1 네 단계

| 단계 | 하는 것 | 과제 | 브랜치 | 상태 |
| --- | --- | --- | --- | --- |
| 1 | `weeklyRanking` job과 그 첫 step `freeze`. 한 랭킹 기간의 구매 활동을 스냅샷으로 고정하고 개수와 체크섬을 남긴다. Batch 6에서의 재시작을 probe로 확인한다. | 2절, 3절 | `feat/weekly-ranking-input` | 인터뷰 끝남(2026-11-17). 구현 끝남(2026-11-17, 1.2절). [명세 237](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237) |
| 2 | 상품별 점수의 chunk step. 6절의 일곱 단계 가운데 실패와 재시작까지. | 4절, 6절 | `feat/weekly-ranking-scores` | 인터뷰 전 |
| 3 | 게시, 게시 포인터, 차수 사이의 순서, `GET /api/v1/rankings/weekly`. | 5절, 1절, 6절의 마지막 단계 | `feat/weekly-ranking-publish` | 인터뷰 전 |
| 4 | 늦은 구매의 새 차수, PG에서 API까지의 연결 테스트. | 7절, 8절 | `feat/weekly-ranking-late-purchase` | 인터뷰 전 |

넷은 모두 `week9`로 PR한다.

### 1.2 1단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [238](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/238) | 셋째 맥락의 용어집과 `CONTEXT-MAP.md`의 계약. 재시작 probe(`BatchRestartProbeJobConfig`, `BatchRestartProbeTest`)와 job 테스트의 기반 `BaseBatchJobTest`. 운영 코드는 없다. |
| [239](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/239) | `WeeklyRankingJobConfig`(job `weeklyRanking`, step `freeze`), `FreezeTasklet`, `WeeklyRankingSnapshotStore`, `SnapshotInputDigest`, `JdbcUtcTimes`, `ClockConfig`, `sql/weekly-ranking-schema.sql`. |
| [240](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/240) | 랭킹 기간의 경계와 soft-delete된 구매 활동의 장면. 스트리머의 `IDX_PURCHASE_ACTIVITY_ON_CONFIRMED_AT`과 그 테스트. 배치의 운영 코드는 바뀌지 않았다. |
| [241](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/241) | `WeeklyRankingJobParametersValidator`와 그 장면 아홉. |
| [242](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/242) | 충돌을 먼저 거절하는 `findConflicting`, `SnapshotKey`, `SnapshotConflictException`. |
| [243](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/243) | 실패한 고정과 고정 도중의 늦은 구매 활동의 장면. 운영 코드는 바뀌지 않았다. |
| [244](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/244) | ADR 0038, 이 문서, `CONTEXT-MAP.md`의 셋째 줄, 끝의 확인(9.3절). |

티켓마다 구현 중에 정한 것은 [글감](../research/week9-weekly-ranking-input-writing-material.md)에 있다.

## 2. 맥락과 계약

주간 랭킹은 셋째 맥락이다. 앱은 commerce-batch이고 용어집은 [`apps/commerce-batch/CONTEXT.md`](../../apps/commerce-batch/CONTEXT.md)다. 랭킹의 publish는 "게시하다"다. "발행하다"는 주문 확정 이벤트에만 쓴다.

구매 활동과는 `purchase_activity` 표로만 이어진다([`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)).

- 구매 활동이 쓰고, 주간 랭킹은 고정할 때만 읽는다.
- 읽는 것은 `order_id`, `product_id`, `quantity`, `confirmed_at`과 `deleted_at is null`이다.
- `confirmed_at`의 인덱스도 계약에 든다. 스트리머의 `PurchaseActivity`가 `IDX_PURCHASE_ACTIVITY_ON_CONFIRMED_AT`을 이름 붙여 선언하고, `PurchaseActivityRepositoryTest`가 `information_schema.statistics`에서 그 하나를 읽는다. 1단계가 다른 맥락의 코드에서 바꾼 것은 이 한 줄과 그 import, 그리고 그 테스트뿐이다.
- commerce-batch는 commerce-streamer에 의존하지 않고 그 클래스를 쓰지 않는다(ADR 0038).

## 3. 표

| 표 | 컬럼 | 키 |
| --- | --- | --- |
| `weekly_ranking_snapshot` | `snapshot_id`(`VARCHAR(100)`), `period_start`(`DATE`), `revision`(`BIGINT`), `state`, `frozen_at`(`DATETIME(6)`), `input_count`, `input_checksum`(`CHAR(64)`) | 기본 키 `snapshot_id`, 유일 키 `UK_WEEKLY_RANKING_SNAPSHOT_ON_PERIOD_START_AND_REVISION (period_start, revision)` |
| `weekly_ranking_snapshot_input` | `snapshot_id`, `order_id`, `product_id`, `quantity`, `confirmed_at`(`DATETIME(6)`) | 기본 키 `(snapshot_id, order_id, product_id)` |

- 자연 키다. 대리 키와 `created_at`·`updated_at`·`deleted_at`이 없다. 스냅샷은 고정한 뒤 바뀌지 않고 지우지 않는다. 엔티티가 아닌 까닭은 ADR 0038에 있다.
- 랭킹 기간의 끝은 저장하지 않는다. 시작에서 이레 뒤다.
- 저장하는 상태는 `FROZEN` 하나다. 진행 중이나 실패를 뜻하는 행은 쓰지 않는다. 뒤의 단계가 제 상태를 더한다.
- 두 표는 `sql/weekly-ranking-schema.sql`이 `CREATE TABLE IF NOT EXISTS`로 만든다. `spring.sql.init`이 `local, test` 블록에서 시작할 때마다 실행한다. 다른 프로필에서는 손으로 적용한다(8절).

## 4. job과 매개변수

- job은 `weeklyRanking`, 1단계의 step은 `freeze` 하나다. job 설정(`WeeklyRankingJobConfig`)과 그 빈들은 템플릿의 데모 job처럼 `spring.batch.job.name=weeklyRanking`일 때만 올라온다.
- 식별 매개변수는 셋이고 모두 사람이 준다: `periodStart`(`LocalDate`), `snapshotId`(`String`), `revision`(`Long`). `RunIdIncrementer`, run ID, 현재 시각은 매개변수에 없다. 그래서 같은 세 값으로 다시 실행하면 같은 `JobInstance`다.
- 실행은 템플릿의 방식이다. `--job.name`과 세 매개변수를 명령줄로 준다. 형을 적지 않은 매개변수는 `String`이므로 `periodStart`와 `revision`에는 형을 붙인다.

  ```
  java -jar <commerce-batch의 bootJar> --job.name=weeklyRanking \
    periodStart=2026-08-17,java.time.LocalDate snapshotId=2026-08-17-r1 revision=1,java.lang.Long
  ```

- `WeeklyRankingJobParametersValidator`가 실행이 생기기 전에 거절한다. 빠진 매개변수, 형이 다른 매개변수, 월요일이 아닌 `periodStart`, 빈 `snapshotId`, 1보다 작은 `revision`이다. 거절은 `JobExecution`도 랭킹의 행도 남기지 않는다. 다만 Batch 6.0.6에서는 실행 없는 `JobInstance`가 남는다(8절).
- 형은 엄격하게 본다. 매개변수의 형은 job 인스턴스를 식별하는 값의 일부여서, `String`으로 받은 날짜를 너그럽게 읽으면 같은 주가 다른 `JobInstance`가 된다. 운영자가 저지를 실수는 `,java.time.LocalDate`를 빠뜨리는 것이라, 거절하는 말에 바른 꼴을 적는다: `periodStart must be a LocalDate, but was a String. Give it as periodStart=2026-08-17,java.time.LocalDate`.
- 랭킹 기간이 끝났는지는 보지 않는다. 시계를 읽는 검사가 되고, 끝나기 전에 고정한 것은 뒤 차수로 바로잡는다. 앞날의 `periodStart`는 빈 스냅샷으로 고정된다.
- step은 애플리케이션의 트랜잭션 매니저(JPA의 것)를 쓴다. step의 쓰기와 그 메타데이터가 함께 커밋된다. `ResourcelessTransactionManager`는 랭킹 job의 어디에도 없다(데모 job에만 있다).
- `FreezeTasklet`은 `@StepScope`이고 세 매개변수를 `jobParameters`에서 받는다.

## 5. 고정

### 5.1 차례

tasklet step 하나, 트랜잭션 하나다. 그 트랜잭션의 격리 수준은 READ COMMITTED다(5.2절).

1. **충돌을 확인한다.** `findConflicting`이 같은 `snapshot_id`, 또는 같은 `(period_start, revision)`의 스냅샷을 잠그지 않고 읽는다. 있으면 `SnapshotConflictException`으로 실패하고 아무것도 쓰지 않는다. 그 말이 step의 exit description에 남는다: `Freeze of snapshot <id> (period …, revision …) conflicts with snapshot <id> (period …, revision …)`. 이 확인을 함께 지나친 두 고정은 뒤진 쪽이 스냅샷을 쓸 때 키가 거절하고, 옮겨 담은 입력도 함께 되돌아간다.
2. **고정 시각을 읽는다.** 주입한 `Clock`에서 한 번 읽고 마이크로초로 자른다.
3. **옮겨 담는다.** `copyInputs`가 `INSERT … SELECT` 한 문장으로 `confirmed_at >= 시작 AND confirmed_at < 끝 AND deleted_at IS NULL`인 구매 활동을 스냅샷 입력에 넣는다. 시작은 `periodStart`의 UTC 0시, 끝은 그 이레 뒤다. 한 문장이라 모든 입력이 한 번의 읽기에서 오고, 본 가장 큰 `id`에 기대는 것이 없다. 한 주문이 여러 상품을 샀으면 상품마다 하나씩 든다.
4. **개수와 체크섬을 구한다.** `digestInputs`가 옮겨 담은 입력을 `order_id`, `product_id` 순으로 흘려 읽으며 `SnapshotInputDigest`에 하나씩 더한다. 목록으로 모으지 않는다. `withFetchSize(Int.MIN_VALUE)`는 Connector/J가 결과를 통째로 받지 않고 행마다 흘려 주게 하는 스위치다. 콜백만으로는 드라이버가 결과 전체를 먼저 받는다.
5. **스냅샷을 쓴다.** `insertFrozenSnapshot`이 `FROZEN` 행을 쓴다.

실패한 고정은 트랜잭션이 되돌려져 두 표에 아무것도 남지 않는다. 그래서 스냅샷이 있다는 것이 곧 입력이 고정됐다는 뜻이다. 다시 실행하면 같은 `JobInstance`로 처음부터 한다. 구매 활동이 없는 랭킹 기간은 개수 0과 빈 입력의 체크섬으로 고정된다.

### 5.2 READ COMMITTED와 인덱스

옮겨 담기는 `purchase_activity`를 읽는 `INSERT … SELECT`다. MySQL은 REPEATABLE READ에서 이 문장의 원본 행에 공유 next-key 잠금을 건다. 명세 237의 "확인한 사실" 1이 MySQL 8.4의 일회용 컨테이너에서 같은 꼴의 표로 잰 결과다. 옮겨 담는 트랜잭션을 열어 둔 채, 다른 연결이 잠금 대기 1초로 한 줄을 넣었다.

| `confirmed_at` 인덱스 | 격리 수준 | 원본 표의 잠금 | 그 랭킹 기간의 늦은 행 넣기 | 다음 주의 행 넣기 |
| --- | --- | --- | --- | --- |
| 없음 | REPEATABLE READ | 기본 키의 모든 행에 S next-key | 막힘 | 막힘 |
| 없음 | READ COMMITTED | 없음 | 됨 | 됨 |
| 있음 | REPEATABLE READ | 인덱스의 그 범위에 S next-key | 막힘 | 됨 |
| 있음 | READ COMMITTED | 없음 | 됨 | 됨 |

스트리머의 연결은 행 잠금을 3초 기다리고(`jpa.yml`의 `innodb_lock_wait_timeout=3`), 구매 활동을 기록하지 못하면 이벤트 읽기를 멈춘다([ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md)). 한 주를 옮겨 담는 동안 그 주의 늦은 구매가 들어오면, REPEATABLE READ에서는 스트리머가 멈출 수 있다. 그래서 step의 트랜잭션은 READ COMMITTED다(`WeeklyRankingJobConfig`의 `transactionAttribute`).

READ COMMITTED에서도 한 문장은 한 번의 일관된 읽기다. 문장이 시작된 뒤에 커밋된 구매 활동은 그 스냅샷에 들지 않는다. 고정한 뒤에 들어온 구매 활동이 그 스냅샷에 들지 않는 것은 "고정하다"의 뜻 그대로다.

인덱스는 잠금이 아니라 읽는 범위를 위해 둔다. 인덱스가 없으면 한 주를 고정하려고 구매 활동의 역사 전체를 훑는다.

### 5.3 시각의 변환

commerce-batch의 JVM 기본 시간대는 Asia/Seoul이다. 명세 237의 "확인한 사실" 2는 Connector/J 9.7.0과 앱과 같은 URL로 `2026-08-17T00:00:00Z`를 넣어 본 결과다.

| JDBC에 묶은 값 | 저장된 `DATETIME(6)` |
| --- | --- |
| `Timestamp`, `Instant`, `OffsetDateTime` | `2026-08-17 09:00:00`. Hibernate가 UTC로 쓴 행과 아홉 시간 어긋난다 |
| UTC의 `LocalDateTime` | `2026-08-17 00:00:00`. 그대로 읽힌다 |

Hibernate의 `jdbc.time_zone: UTC`는 `JdbcClient`에 닿지 않는다. 그래서 모든 시각은 UTC의 `LocalDateTime`으로 JDBC를 지나고, 변환은 `JdbcUtcTimes` 한 곳에 있다. 랭킹 기간의 경계 장면(9.1절)이 JVM 시간대가 Asia/Seoul임을 먼저 단언하고, 두 방향의 어긋남을 모두 잡는다(9.2절).

### 5.4 체크섬

- 과제 6절의 정규 텍스트의 SHA-256, 소문자 16진수 64자다.
- 행은 `orderId`, `productId` 순이다. 필드는 `orderId`, `productId`, `quantity`, `confirmedAt`이고 TAB으로 잇는다. 모든 행 뒤에, 마지막 행 뒤에도 LF를 둔다. 인코딩은 UTF-8이다.
- `confirmedAt`은 UTC이고 소수 여섯 자리를 늘 적는다: `2026-08-17T00:00:00.000000Z`. 한 순간에 한 표기다.
- 빈 입력의 체크섬은 빈 문자열의 SHA-256(`e3b0c442…b855`)이다.
- `SnapshotInputDigest`는 SQL과 떨어진 순수한 코드다. DB 없이 값으로 확인한다(`SnapshotInputDigestTest`).

과제 6절의 네 입력은 확정 시각을 주지 않는다. 테스트는 이 값을 골랐다: 주문 42는 `2026-08-17T00:00:00Z`(두 상품), 주문 43은 `2026-08-19T13:45:30.123456Z`, 주문 44는 `2026-08-23T23:59:59.999999Z`. 소수가 없는 시각, 마이크로초가 있는 시각, 랭킹 기간의 마지막 마이크로초다. 그 체크섬은 `d07eff39…4720b`이고, 코드와 떨어져 `printf … | shasum -a 256`으로 구했다.

## 6. 재시작 probe

과제 3절의 probe 예시는 Boot 3.4.4와 Batch 5.2.2로 쓰였다. 이 저장소는 Boot 4.1.2와 Batch 6.0.6이다. 그래서 같은 장면을 테스트 소스에만 있는 job(`BatchRestartProbeJobConfig`, job `batchRestartProbe`)으로 옮겨 실제로 확인했다.

- `batch_restart_probe`의 상품 7, 8, 9를 `JdbcCursorItemReaderBuilder`의 리더(이름 `purchaseProductProbe`)로 `chunk(2)`씩 읽고, 처리한 상품을 쓴 실행과 함께 `batch_restart_probe_written`에 쓴다. 리더의 매개변수 `minProductId`는 step scope로 묶는다. step은 애플리케이션의 트랜잭션 매니저를 쓴다.
- 첫 실행은 상품 9 앞에서 실패한다. `FAILED`이고, 쓴 표에 7과 8이 있으며, DB에서 다시 읽은 step `ExecutionContext`의 `purchaseProductProbe.read.count`가 2다.
- 메타데이터를 지우지 않고 같은 매개변수로 다시 실행한다. 같은 `JobInstance`, 새 `JobExecution`, `COMPLETED`다. 쓴 표에는 7, 8, 9가 한 번씩 있고, 7과 8은 첫 실행이, 9는 재시작이 썼다.
- 리더에 `.saveState(false)`를 주면 두 장면이 모두 실패한다. 첫 장면은 저장된 개수를 찾지 못하고, 재시작은 7과 8을 다시 쓴다.

Batch 6.0.6에서 확인한 이름: `StepBuilder.chunk(int)`는 Batch 6의 `ChunkOrientedStepBuilder`를 돌려주고, 리더의 저장 키는 `<이름>.read.count`다. 이것이 뒤의 단계가 기대는 네 사실(같은 인스턴스, 새 실행, 저장된 읽은 수, 남은 항목만 처리)이다.

## 7. 고르지 않은 것

엔티티와 `ddl-auto`의 재정의, Flyway, 스트리머의 엔티티에 대한 의존은 ADR 0038이 적는다. 나머지는 이렇다.

- **REPEATABLE READ로 옮겨 담기.** 5.2절. 그 랭킹 기간의 늦은 구매가 막히고, 스트리머는 3초 뒤 이벤트 읽기를 멈춘다.
- **구매 활동을 메모리로 읽어 한 번에 쓰기.** 한 주의 구매 활동을 모두 메모리에 든다. 실제 규모에서는 비싸다. 개수와 체크섬도 흘려 읽으며 구한다.
- **`max(id)`까지 나누어 옮겨 담기.** 과제가 금한다. 커밋 순서와 `id`의 순서가 같지 않아, 본 가장 큰 `id` 아래에 늦게 커밋된 행이 남을 수 있다.
- **충돌을 잠그는 읽기로 확인하기.** 유일 키에 gap 잠금이 더해진다. 키가 이미 해결하는 경쟁을 위한 잠금이다.
- **랭킹 기간이 끝났는지의 검사.** 4절.
- **거절된 매개변수의 `JobInstance`를 막기.** `JobOperator.start`를 감싸 검증을 먼저 하는 것, 거절한 뒤 인스턴스를 지우는 것, Boot 4.2.0-RC2로 먼저 올리는 것. 앞의 둘은 Batch를 올릴 때까지만 있을 코드이고, 셋째는 Initializr의 기본을 앞지른다([명세 237](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237)의 두 번째 인터뷰).
- **probe를 `JobOperator.restart(JobExecution)`로 다시 시작하기.** 명세의 "확인한 사실" 4는 이 메서드를 적는다. 그러나 랭킹 job은 템플릿대로 `--job.name`과 같은 매개변수로 다시 실행되고, 그것은 시작이다. 그래서 probe는 같은 매개변수의 두 번째 `startJob`으로 그 길을 증명한다. `restart(…)`는 쓰지 않았다.

## 8. 한계

- **옮겨 담기는 한 문장이고 한 트랜잭션이다.** 한 주의 구매 활동이 클수록 그 트랜잭션이 길다. 나누어 옮기지 않는다. READ COMMITTED라 스트리머를 막지는 않는다(5.2절).
- **랭킹의 표는 `local`, `test` 밖에서 손으로 적용한다.** Batch 메타데이터와 같다(ADR 0038).
- **"끝난 고정은 다시 실행되지 않는다"는 이 단계에서 증명하지 않는다.** 고정 뒤에 실패할 step이 없기 때문이다. 이 단계의 job은 `freeze`로 끝나고, 끝난 실행을 같은 값으로 다시 실행하면 `JobInstanceAlreadyCompleteException`이다. 2단계(`feat/weekly-ranking-scores`)의 과제 6절 장면이 증명한다.
- **이 단계에서 끝난 `JobInstance`는 step이 더해진 뒤에 이어 실행할 수 없다.** 이미 `COMPLETED`라서다. 메타데이터를 단계 사이에 보존하는 환경이 없으므로 받아들인다. 같은 랭킹 기간은 새 `snapshotId`와 차수로 다시 고정한다.
- **거절된 매개변수는 Batch 6.0.6에서 실행 없는 `JobInstance`를 남긴다.** `TaskExecutorJobLauncher.createJobExecution`이 `createJobInstance`를 제 트랜잭션으로 커밋한 뒤에 검증기를 부르기 때문이다. 같은 나쁜 매개변수로 다시 실행하면 검증기의 말이 아니라 `IllegalStateException`("Cannot find any job execution for job instance")이 난다. 이런 인스턴스는 언제나 거절될 매개변수에만 생기므로 바른 실행을 막지 않는다. [Spring Batch issue 5562](https://github.com/spring-projects/spring-batch/issues/5562)이고, 커밋 `4dcf68f7b`가 6.1.0-RC1에서 고쳤다. Boot 4.2와 함께 오면 사라지고, 그때 이 저장소에서 지울 코드는 없다. 테스트는 이 인스턴스를 고정하지 않는다.
- **처리량은 재지 않았고 주장하지 않는다.** 테스트의 입력은 몇 줄이다.

## 9. 검증

job 테스트는 `BaseBatchJobTest`([ADR 0012](../adr/0012-tests-inherit-setup-from-abstract-base-classes.md))를 상속한다. `@SpringBootTest`에 `spring.batch.job.enabled=false`, `@SpringBatchTest`, MySQL과 Redis의 Testcontainers다. `JobOperatorTestUtils`로 실행하고, 남은 것을 읽는다: 스냅샷과 스냅샷 입력의 행, Batch 메타데이터의 실행 상태와 `ExecutionContext`. DB를 mock으로 대신하지 않는다. `purchase_activity`는 테스트 전용 `sql/purchase-activity.sql`이 만든다(ADR 0038의 대가). 테스트 사이에 랭킹의 두 표와 `purchase_activity`를 비운다.

### 9.1 장면

| 이음매 | 클래스 | 장면 |
| --- | --- | --- |
| 체크섬(DB 없음) | `SnapshotInputDigestTest` | 과제 6절의 네 입력의 정규 텍스트와 체크섬, 빈 입력, 소수 여섯 자리. 3개 |
| `weeklyRanking` job | `WeeklyRankingFreezeTest` | 23개. 아래 |
| 재시작 probe | `BatchRestartProbeTest` | 첫 실행의 실패와 저장된 읽은 수, 같은 인스턴스의 재시작. 2개 |
| 스트리머의 인덱스 | `PurchaseActivityRepositoryTest` | `confirmed_at`의 단일 컬럼 인덱스가 이름대로 있다. 1개 |

`WeeklyRankingFreezeTest`의 장면은 이렇다.

- **고정(239):** 과제의 주를 고정하면 `COMPLETED`이고 주문과 상품마다 입력 하나다. 스냅샷은 `FROZEN`, 개수 4, DB 없는 테스트와 같은 체크섬, 준 랭킹 기간과 차수, 테스트 시계의 시각(`2026-08-24T01:02:03.123456789Z`가 `2026-08-24 01:02:03.123456`으로 저장된다). 구매 활동이 없는 랭킹 기간은 개수 0과 빈 입력의 체크섬이다. 스키마 스크립트를 다시 실행해도 실패하지 않고 행이 남는다.
- **경계(240):** 월요일 0시 UTC의 구매 활동은 들고, 그보다 1마이크로초 앞선 것과 다음 월요일 0시의 것은 들지 않는다. JVM 시간대가 Asia/Seoul인 채로 돌린다. soft-delete된 구매 활동은 입력에도 개수와 체크섬에도 없다.
- **실패와 늦은 구매(243):** 이음매는 `WeeklyRankingSnapshotStore.digestInputs`다. 옮겨 담기와 스냅샷 쓰기 사이의 하나뿐인 호출이고, springmockk의 `@MockkSpyBean`이 그 자리에서 실패시키거나 다른 연결의 넣기를 실행한다.
  - 입력을 쓴 뒤 실패한 고정은 `FAILED`이고 두 표에 그 `snapshotId`의 행이 없다. 같은 매개변수로 다시 실행하면 같은 `JobInstance`, 새 실행으로 `COMPLETED`다.
  - 고정 도중 다른 연결이 잠금 대기 1초로 그 랭킹 기간의 구매 활동(주문 45)을 넣는다. 넣기는 성공하고, 그 행은 스냅샷 입력에 없으며 개수 4와 과제의 체크섬은 그대로다.
- **충돌(242):** 같은 `snapshotId`와 다른 랭킹 기간, 같은 `snapshotId`와 같은 랭킹 기간의 다른 차수, 같은 랭킹 기간·차수와 다른 `snapshotId`. 셋 다 `FAILED`이고, exit description이 충돌을 말하며, 있던 스냅샷과 그 입력이 그대로이고 새 행이 없다. 새 `snapshotId`의 새 차수는 그대로 고정된다. SQL로 같은 `(period_start, revision)`의 둘째 행을 넣으면 DB가 그 유일 키의 이름으로 거절한다.
- **매개변수(241):** 화요일의 `periodStart`, 빈 `snapshotId`, `revision` 0, 빠진 매개변수 셋, `String`으로 준 `periodStart`는 `InvalidJobParametersException`이고 `JobExecution`과 두 표의 행이 없다. 앞날의 `periodStart`는 받아들인다. 끝난 실행을 같은 세 값으로 다시 실행하면 `JobInstanceAlreadyCompleteException`이고 두 표는 그대로다.

### 9.2 붉은 확인

커밋하지 않은 로컬 수정으로 돌리고 되돌렸다(2026-11-17). 티켓마다의 기록은 글감에 있다.

| 수정 | 붉어진 장면 |
| --- | --- |
| 리더에 `.saveState(false)` | probe의 두 장면. 저장된 읽은 수가 없고, 재시작이 7과 8을 다시 쓴다 |
| step의 격리 수준을 `ISOLATION_REPEATABLE_READ`로 | 늦은 구매 장면 하나. 1초 뒤 `MySQLTransactionRollbackException: Lock wait timeout exceeded`(5.2절의 셋째 줄) |
| step을 `ResourcelessTransactionManager`로 | 실패한 고정의 두 장면. 실패한 실행이 입력 넷을 남기고, 재시작도 `FAILED`다 |
| 랭킹 기간의 `Instant`를 JDBC에 그대로 묶기 | 경계 장면. 입력이 주문 61이 아니라 62다. 아홉 시간의 어긋남이다 |
| `confirmed_at`을 `getTimestamp(…).toInstant()`로 읽기 | 경계 장면. 체크섬이 `b75aefc5…`가 된다 |
| `deleted_at IS NULL`을 지우기 | soft-delete 장면. 주문 45가 입력에 든다 |
| 스트리머의 인덱스 선언을 지우기(엔티티를 고치기 전) | `PurchaseActivityRepositoryTest`의 인덱스 장면 |

충돌의 확인(242)은 상태가 아니라 exit description으로 붉었다. 확인이 없을 때도 두 충돌은 `FAILED`였다. 옮겨 담은 뒤 스냅샷의 기본 키나 유일 키가 거절했기 때문이다. 키는 이미 뒤의 방어로 일했고, 이 확인이 더한 것은 쓰기 전의 거절과 무엇이 길을 막는지 말하는 거절이다.

### 9.3 끝의 확인

```
scripts/final-check --app commerce-batch --app commerce-streamer
```

- 2026-11-17 `bfc0e186`의 깨끗한 트리에서 돌렸다(12:53:59Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다(4 s).
- commerce-batch는 5개 클래스, 31개 테스트, 실패 0, 오류 0, 건너뜀 0이다(25 s). 템플릿은 2개 클래스, 3개 테스트(`CommerceBatchApplicationTest`, `DemoJobE2ETest`)였으므로 이 단계가 3개 클래스와 28개 테스트를 더했다.
- commerce-streamer는 18개 클래스, 68개 테스트, 실패 0, 오류 0, 건너뜀 0이다(53 s). 이 단계가 인덱스 장면 1개를 더했다.
- `bfc0e186`의 코드는 `25c38bd7`의 것이고, 그 뒤로는 문서만 바뀌었다.

## 10. 명세와 다른 것

2026-11-17 구현을 마치고 명세 237과 견준 것이다.

| 명세 | 구현 | 어디서 |
| --- | --- | --- |
| `performance_schema.data_locks`로 잠금이 없음을 확인한다(인터뷰) | 고정 도중 다른 연결의 늦은 넣기가 성공하는 것으로 본다. Testcontainers의 일반 사용자는 그 표를 읽지 못한다. 명세의 "확인한 사실" 5가 이미 적었다 | 명세 작성 |
| 거절된 매개변수는 `JobInstance`가 생기기 전에 거절한다(인터뷰) | `JobExecution`과 랭킹의 행이 생기기 전에 거절한다. 실행 없는 `JobInstance`는 남는다(8절). 명세의 "확인한 사실" 6과 이야기 2를 두 번째 인터뷰가 고쳤다 | 241 |
| probe는 `JobOperator.restart(JobExecution)`을 쓴다("확인한 사실" 4) | 같은 매개변수의 두 번째 `startJob`(7절) | 238 |
| 실패와 늦은 구매의 이음매는 구현에서 정한다 | `WeeklyRankingSnapshotStore.digestInputs` | 239, 243 |
| 같은 세 값의 스냅샷은 이 단계의 job에서 일어나지 않는다 | Batch가 먼저 거절한다. `findConflicting`은 그것도 충돌로 보고, 예외를 두지 않는다 | 242 |
| `CONTEXT-MAP.md`의 셋째 줄 | 238의 커밋은 셋째 맥락의 문단만 더하고 표의 줄을 빠뜨렸다. 244가 더했다 | 244 |

## 11. 2단계로 넘기는 것

- 점수 step의 리더가 쓸 `(snapshot_id, product_id)` 인덱스. 이 단계의 스냅샷 입력에는 기본 키뿐이다.
- "끝난 고정은 다시 실행되지 않는다"의 증명(8절). 점수 step이 실패한 뒤 다시 시작해도 `freeze`의 입력이 다시 담기지 않고 체크섬이 같은지를 본다.
- 스냅샷의 새 상태. 이 단계는 `FROZEN`만 쓴다.
- probe가 확인한 네 사실(6절)은 점수 step의 chunk와 리더가 기댄다. 리더의 이름과 `<이름>.read.count`, `chunk(int)`가 돌려주는 빌더가 그대로인지 그 단계에서 다시 본다.
