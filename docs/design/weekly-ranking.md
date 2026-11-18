# 주간 랭킹 설계 — 랭킹 기간의 입력을 고정하고, 중단된 배치를 이어서 같은 랭킹을 만든다

9주차 과제의 설계 문서다. 용어는 주간 랭킹의 [`CONTEXT.md`](../../apps/commerce-batch/CONTEXT.md)("랭킹 기간", "스냅샷", "스냅샷 입력", "차수", "체크섬", "충돌", "고정하다", "게시하다")와, 구매 활동의 [`CONTEXT.md`](../../apps/commerce-streamer/CONTEXT.md)를 따른다. 결정의 출처는 1단계가 [명세 237](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237)이다. 1단계는 [ADR 0038](../adr/0038-weekly-ranking-tables-are-not-entities.md)을 둔다. READ COMMITTED와 인덱스는 ADR이 아니라 이 문서의 5.2절이 적는다. (2026-11-18 2단계: 결정의 출처는 [명세 246](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/246)이고, "점수"를 용어집에 더했다. ADR은 더하지 않았다. 12절.)

상태: 2026-11-17 1단계(`feat/weekly-ranking-input`)를 인터뷰하고, 같은 날 그 일곱 티켓을 구현한 뒤 이 문서를 썼다. (2026-11-18 2단계(`feat/weekly-ranking-scores`)를 인터뷰하고, 같은 날 그 네 티켓을 구현한 뒤 12절을 더했다.)

## 1. 근거와 범위

- 과제: 9주차 실습 "중단된 배치를 이어서 같은 랭킹 만들기". 1단계의 몫은 과제 2절(입력 고정)과 과제 3절(job의 배선과 재시작 probe)이다.
- 6주차와 7주차로 확정된 주문의 구매 활동이 `purchase_activity`에 쌓인다. 주간 랭킹은 그것을 읽어 commerce-batch에서 만든다. 주문, 결제, 아웃박스, 구매 활동의 기록은 바뀌지 않는다. 스트리머에서 바뀐 것은 인덱스 한 줄이다(2절).
- 데모 job(`DemoJobConfig`, `DemoTasklet`, `DemoJobE2ETest`)은 그대로 둔다. `spring.batch.job.name=demoJob`일 때만 올라오므로 `weeklyRanking`의 실행에는 없다. 지우는 일은 [issue 236](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/236)이다.

### 1.1 네 단계

| 단계 | 하는 것 | 과제 | 브랜치 | 상태 |
| --- | --- | --- | --- | --- |
| 1 | `weeklyRanking` job과 그 첫 step `freeze`. 한 랭킹 기간의 구매 활동을 스냅샷으로 고정하고 개수와 체크섬을 남긴다. Batch 6에서의 재시작을 probe로 확인한다. | 2절, 3절 | `feat/weekly-ranking-input` | 인터뷰 끝남(2026-11-17). 구현 끝남(2026-11-17, 1.2절). [명세 237](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237) |
| 2 | 상품별 점수의 chunk step. 6절의 일곱 단계 가운데 실패와 재시작까지. | 4절, 6절 | `feat/weekly-ranking-scores` | 인터뷰 끝남(2026-11-18). 구현 끝남(2026-11-18, 1.3절, 12절). [명세 246](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/246) |
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

### 1.3 2단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [247](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/247) | `WeeklyRankingJobConfig`의 둘째 step `score`, `ScoreProductReader`, `ScoreProcessor`, `ScoreWriter`, `WeeklyRankingScoreStore`, `WeeklyRankingScoreProperties`. 스키마 스크립트의 `weekly_ranking_score`와 스냅샷 입력의 보조 인덱스. `WeeklyRankingScoreTest`와 랭킹 테스트의 도우미 `WeeklyRankingJobs`. 앞선 두 커밋이 용어집의 "점수"와 `CODING_STANDARDS.md`의 긴 인덱스 이름 규칙(12.2절)을 더했다. |
| [248](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/248) | 틀린 점수의 장면이 점수를 읽기 전에 실행의 `COMPLETED`를 단언한다. 장면과 그 붉은 확인은 247의 커밋에 이미 있었다. 운영 코드는 바뀌지 않았다. |
| [249](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/249) | `WeeklyRankingJobRestartTest`와 그 장면 여섯. 운영 코드는 바뀌지 않았다. |
| [250](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/250) | 이 문서의 12절, 1단계의 절에 붙인 2단계의 메모, 끝의 확인(12.8절). |

티켓마다 구현 중에 정한 것은 [2단계의 글감](../research/week9-weekly-ranking-scores-writing-material.md)에 있다.

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
- 저장하는 상태는 `FROZEN` 하나다. 진행 중이나 실패를 뜻하는 행은 쓰지 않는다. 뒤의 단계가 제 상태를 더한다. (2026-11-18 2단계: 점수의 단계는 상태를 더하지 않았다. 12.3절.)
- 두 표는 `sql/weekly-ranking-schema.sql`이 `CREATE TABLE IF NOT EXISTS`로 만든다. `spring.sql.init`이 `local, test` 블록에서 시작할 때마다 실행한다. 다른 프로필에서는 손으로 적용한다(8절). (2026-11-18 2단계: 같은 스크립트가 점수 표 `weekly_ranking_score`를 만들고, 스냅샷 입력에 보조 인덱스를 둔다. 12.2절.)

## 4. job과 매개변수

- job은 `weeklyRanking`, 1단계의 step은 `freeze` 하나다. job 설정(`WeeklyRankingJobConfig`)과 그 빈들은 템플릿의 데모 job처럼 `spring.batch.job.name=weeklyRanking`일 때만 올라온다. (2026-11-18 2단계: job은 `freeze` 다음에 `score`를 실행한다. 매개변수와 검증기는 이 절 그대로다. 12.1절.)
- 식별 매개변수는 셋이고 모두 사람이 준다: `periodStart`(`LocalDate`), `snapshotId`(`String`), `revision`(`Long`). `RunIdIncrementer`, run ID, 현재 시각은 매개변수에 없다. 그래서 같은 세 값으로 다시 실행하면 같은 `JobInstance`다.
- 실행은 템플릿의 방식이다. `--job.name`과 세 매개변수를 명령줄로 준다. 형을 적지 않은 매개변수는 `String`이므로 `periodStart`와 `revision`에는 형을 붙인다.

  ```
  java -jar <commerce-batch의 bootJar> --job.name=weeklyRanking \
    periodStart=2026-08-17,java.time.LocalDate snapshotId=2026-08-17-r1 revision=1,java.lang.Long
  ```

- `WeeklyRankingJobParametersValidator`가 실행이 생기기 전에 거절한다. 빠진 매개변수, 형이 다른 매개변수, 월요일이 아닌 `periodStart`, 빈 `snapshotId`, 1보다 작은 `revision`이다. (2026-11-17 명세 237의 닫는 검토: 100자를 넘는 `snapshotId`도 거절한다. 전에는 검증을 지나 옮겨 담기가 `VARCHAR(100)`에서 실패해, 거절이 아니라 `FAILED` 실행을 남겼다.) 거절은 `JobExecution`도 랭킹의 행도 남기지 않는다. 다만 Batch 6.0.6에서는 실행 없는 `JobInstance`가 남는다(8절).
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

옮겨 담기는 `purchase_activity`를 읽는 `INSERT … SELECT`다. MySQL은 REPEATABLE READ에서 이 문장이 읽는 행에 공유 next-key 잠금을 건다. 명세 237의 "확인한 사실" 1이 MySQL 8.4의 일회용 컨테이너에서 같은 꼴의 표로 잰 결과다. 옮겨 담는 트랜잭션을 열어 둔 채, 다른 연결이 잠금 대기 1초로 한 줄을 넣었다.

| `confirmed_at` 인덱스 | 격리 수준 | 읽는 표의 잠금 | 그 랭킹 기간의 늦은 행 넣기 | 다음 주의 행 넣기 |
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
- **"끝난 고정은 다시 실행되지 않는다"는 이 단계에서 증명하지 않는다.** 고정 뒤에 실패할 step이 없기 때문이다. 이 단계의 job은 `freeze`로 끝나고, 끝난 실행을 같은 값으로 다시 실행하면 `JobInstanceAlreadyCompleteException`이다. 2단계(`feat/weekly-ranking-scores`)의 과제 6절 장면이 증명한다. (2026-11-18 2단계: 증명했다. `score`에서 실패한 실행을 다시 실행하면 그 인스턴스의 `freeze` step 실행은 하나뿐이고, 스냅샷과 그 입력은 시계를 옮기고 구매 활동을 더한 뒤에도 첫 실행의 것 그대로다. 12.3절, 12.6절.)
- **이 단계에서 끝난 `JobInstance`는 step이 더해진 뒤에 이어 실행할 수 없다.** 이미 `COMPLETED`라서다. 메타데이터를 단계 사이에 보존하는 환경이 없으므로 받아들인다. 같은 랭킹 기간은 새 `snapshotId`와 차수로 다시 고정한다. (2026-11-18 2단계: 2단계에서 끝난 인스턴스도 `publish`가 더해진 뒤에 같다. 12.5절.)
- **거절된 매개변수는 Batch 6.0.6에서 실행 없는 `JobInstance`를 남긴다.** `TaskExecutorJobLauncher.createJobExecution`이 `createJobInstance`를 제 트랜잭션으로 커밋한 뒤에 검증기를 부르기 때문이다. 같은 나쁜 매개변수로 다시 실행하면 검증기의 말이 아니라 `IllegalStateException`("Cannot find any job execution for job instance")이 난다. 이런 인스턴스는 언제나 거절될 매개변수에만 생기므로 바른 실행을 막지 않는다. [Spring Batch issue 5562](https://github.com/spring-projects/spring-batch/issues/5562)이고, 커밋 `4dcf68f7b`가 6.1.0-RC1에서 고쳤다. Boot 4.2와 함께 오면 사라지고, 그때 이 저장소에서 지울 코드는 없다. 테스트는 이 인스턴스를 고정하지 않는다.
- **처리량은 재지 않았고 주장하지 않는다.** 테스트의 입력은 몇 줄이다. (2026-11-18 2단계: `score`의 chunk 크기 100도 재지 않은 값이다. 2단계의 한계는 12.5절.)

## 9. 검증

job 테스트는 `BaseBatchJobTest`([ADR 0012](../adr/0012-tests-inherit-setup-from-abstract-base-classes.md))를 상속한다. `@SpringBootTest`에 `spring.batch.job.enabled=false`, `@SpringBatchTest`, MySQL과 Redis의 Testcontainers다. `JobOperatorTestUtils`로 실행하고, 남은 것을 읽는다: 스냅샷과 스냅샷 입력의 행, Batch 메타데이터의 실행 상태와 `ExecutionContext`. DB를 mock으로 대신하지 않는다. `purchase_activity`는 테스트 전용 `sql/purchase-activity.sql`이 만든다(ADR 0038의 대가). 테스트 사이에 랭킹의 두 표와 `purchase_activity`를 비운다.

### 9.1 장면

| 이음매 | 클래스 | 장면 |
| --- | --- | --- |
| 체크섬(DB 없음) | `SnapshotInputDigestTest` | 과제 6절의 네 입력의 정규 텍스트와 체크섬, 빈 입력, 소수 여섯 자리. 3개 |
| `weeklyRanking` job | `WeeklyRankingFreezeTest` | 25개. 아래 |
| 재시작 probe | `BatchRestartProbeTest` | 첫 실행의 실패와 저장된 읽은 수, 같은 인스턴스의 재시작. 2개 |
| 스트리머의 인덱스 | `PurchaseActivityRepositoryTest` | `confirmed_at`의 단일 컬럼 인덱스가 이름대로 있다. 1개 |

`WeeklyRankingFreezeTest`의 장면은 이렇다.

- **고정(239):** 과제의 주를 고정하면 `COMPLETED`이고 주문과 상품마다 입력 하나다. 스냅샷은 `FROZEN`, 개수 4, DB 없는 테스트와 같은 체크섬, 준 랭킹 기간과 차수, 테스트 시계의 시각(`2026-08-24T01:02:03.123456789Z`가 `2026-08-24 01:02:03.123456`으로 저장된다). 구매 활동이 없는 랭킹 기간은 개수 0과 빈 입력의 체크섬이다. 스키마 스크립트를 다시 실행해도 실패하지 않고 행이 남는다.
- **경계(240):** 월요일 0시 UTC의 구매 활동은 들고, 그보다 1마이크로초 앞선 것과 다음 월요일 0시의 것은 들지 않는다. JVM 시간대가 Asia/Seoul인 채로 돌린다. soft-delete된 구매 활동은 입력에도 개수와 체크섬에도 없다.
- **실패와 늦은 구매(243):** 이음매는 `WeeklyRankingSnapshotStore.digestInputs`다. 옮겨 담기와 스냅샷 쓰기 사이의 하나뿐인 호출이고, springmockk의 `@MockkSpyBean`이 그 자리에서 실패시키거나 다른 연결의 넣기를 실행한다.
  - 입력을 쓴 뒤 실패한 고정은 `FAILED`이고 두 표에 그 `snapshotId`의 행이 없다. 같은 매개변수로 다시 실행하면 같은 `JobInstance`, 새 실행으로 `COMPLETED`다.
  - 고정 도중 다른 연결이 잠금 대기 1초로 그 랭킹 기간의 구매 활동(주문 45)을 넣는다. 넣기는 성공하고, 그 행은 스냅샷 입력에 없으며 개수 4와 과제의 체크섬은 그대로다.
- **충돌(242):** 같은 `snapshotId`와 다른 랭킹 기간, 같은 `snapshotId`와 같은 랭킹 기간의 다른 차수, 같은 랭킹 기간·차수와 다른 `snapshotId`. 셋 다 `FAILED`이고, exit description이 충돌을 말하며, 있던 스냅샷과 그 입력이 그대로이고 새 행이 없다. 새 `snapshotId`의 새 차수는 그대로 고정된다. SQL로 같은 `(period_start, revision)`의 둘째 행을 넣으면 DB가 그 유일 키의 이름으로 거절한다. (2026-11-17 명세 237의 닫는 검토: 같은 `snapshot_id`의 둘째 행을 기본 키가 거절하는 장면을 더했다. 이야기 21의 두 키가 모두 테스트에 있다.)
- **매개변수(241):** 화요일의 `periodStart`, 빈 `snapshotId`, `revision` 0, 빠진 매개변수 셋, `String`으로 준 `periodStart`(2026-11-17 닫는 검토 뒤로는 100자를 넘는 `snapshotId`도)는 `InvalidJobParametersException`이고 `JobExecution`과 두 표의 행이 없다. 앞날의 `periodStart`는 받아들인다. 끝난 실행을 같은 세 값으로 다시 실행하면 `JobInstanceAlreadyCompleteException`이고 두 표는 그대로다.

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
- (2026-11-17 명세 237의 닫는 검토) 검토의 고침(`a0da8f4e`)이 운영 코드를 바꿨으므로 그 깨끗한 트리에서 다시 돌렸다(13:39:41Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했다(1 s). commerce-batch는 5개 클래스, 33개 테스트, 실패 0, 오류 0, 건너뜀 0이다(18 s). 위의 31개에 100자를 넘는 `snapshotId`의 거절과 `snapshot_id` 기본 키의 장면이 더해졌다. commerce-streamer는 18개 클래스, 68개 테스트, 실패 0, 오류 0, 건너뜀 0이다(49 s). 이것이 1단계의 끝의 확인이다. 위의 `bfc0e186`의 실행은 앞의 확인이다. `a0da8f4e` 뒤로는 문서만 바뀌었다.

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
| 테스트 컨텍스트를 같은 DB에 두 번 띄워도 스키마 스크립트가 실패하지 않는다(티켓 239) | 둘째 컨텍스트를 띄우지 않고, 시작 때 `spring.sql.init`이 부르는 빈 `ApplicationDataSourceScriptDatabaseInitializer`의 `initializeDatabase()`를 고정 뒤에 다시 부른다. 같은 스크립트가 같은 길로 돈다(2026-11-17 닫는 검토에서 이 표에 더했다) | 239 |
| 빈 `snapshotId`와 1보다 작은 `revision`을 거절한다(이야기 3) | 100자를 넘는 `snapshotId`도 거절한다(4절의 메모) | 2026-11-17 닫는 검토 |

## 11. 2단계로 넘기는 것

- 점수 step의 리더가 쓸 `(snapshot_id, product_id)` 인덱스. 이 단계의 스냅샷 입력에는 기본 키뿐이다. (2026-11-18 2단계: 닫았다. 보조 인덱스 `(snapshot_id, product_id, quantity)`다. 12.2절.)
- "끝난 고정은 다시 실행되지 않는다"의 증명(8절). 점수 step이 실패한 뒤 다시 시작해도 `freeze`의 입력이 다시 담기지 않고 체크섬이 같은지를 본다. (2026-11-18 2단계: 닫았다. 12.3절, 12.6절.)
- 스냅샷의 새 상태. 이 단계는 `FROZEN`만 쓴다. (2026-11-18 2단계: 더하지 않기로 했다. 상태는 `FROZEN` 그대로다. 12.3절.)
- probe가 확인한 네 사실(6절)은 점수 step의 chunk와 리더가 기댄다. 리더의 이름과 `<이름>.read.count`, `chunk(int)`가 돌려주는 빌더가 그대로인지 그 단계에서 다시 본다. (2026-11-18 2단계: 진짜 job에서 다시 봤다. 12.3절.)

(2026-11-18 2단계: 3단계로 넘기는 것은 12.10절에 있다.)

## 12. 점수 (2단계)

결정의 출처는 [명세 246](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/246)이다(2026-11-18 인터뷰, 질문 13개). 과제의 몫은 4절(chunk step)과, 6절의 일곱 단계 가운데 여섯째까지다. ADR은 더하지 않았다. 리더의 종류, chunk 크기, 인덱스는 모두 한 곳만 고치면 되돌릴 수 있다. 용어집은 "점수"를 더했다: 한 스냅샷의 스냅샷 입력에서 한 상품의 수량을 모두 더한 값이고, 스냅샷과 상품 한 쌍에 하나다. 과제의 "score stage"에서 "stage"는 용어집에도 표 이름에도 옮기지 않았다.

### 12.1 job과 `score` step

- `weeklyRanking`은 `freeze` 다음에 `score`를 실행하고, 이 브랜치에서는 `score`로 끝난다. 식별 매개변수 셋과 검증기는 4절 그대로다.
- `score`는 chunk step이다. `StepBuilder.chunk<Long, ProductScore>(chunkSize)`가 돌려주는 `ChunkOrientedStepBuilder`에 애플리케이션의 트랜잭션 매니저를 준다. probe가 Batch 6.0.6에서 확인한 꼴(6절)이다. (2026-11-18 명세 246의 닫는 검토: chunk의 항목 `ProductScore`는 프로세서·라이터·job 설정이 함께 쓰므로 `SnapshotInput`처럼 제 파일 `ProductScore.kt`에 둔다. 전에는 `WeeklyRankingScoreStore.kt`의 끝에 있었다.)
- 격리 수준은 따로 주지 않는다. `freeze`가 READ COMMITTED인 것은 `purchase_activity`를 읽기 때문이었고(5.2절), `score`는 고정된 스냅샷 입력과 제 점수 표만 읽고 쓴다.
- skip과 retry를 두지 않는다. 어떤 실패든 step이 `FAILED`가 되고, 실행도 `FAILED`로 끝난다.
- 한 chunk의 점수와 리더의 읽은 수는 한 트랜잭션에 커밋된다. 그래서 저장된 읽은 수는 점수가 없는 상품을 지났다고 말하지 않고, 점수가 있는 상품을 빠뜨리지도 않는다.

| 부분 | 클래스 | 하는 것 |
| --- | --- | --- |
| 리더 | `ScoreProductReader` | `JdbcCursorItemReader<Long>`를 상속한 `@StepScope` 빈. `SELECT DISTINCT product_id … WHERE snapshot_id = ? ORDER BY product_id`로 그 스냅샷의 상품을 상품 ID 순으로 한 번씩 읽는다. 한 항목이 한 상품이다. `snapshotId`는 `jobParameters`에서 받는다. 이름은 `scoreProductReader`이고, 상태를 저장한다 |
| 프로세서 | `ScoreProcessor` | 상품마다 `WeeklyRankingScoreStore.sumQuantity(snapshotId, productId)`를 한 번 부른다. 그 스냅샷 입력의 `quantity`를 더하는 질의 하나다. `purchase_activity`는 읽지 않는다 |
| 라이터 | `ScoreWriter` | chunk를 `WeeklyRankingScoreStore.saveScores`에 넘긴다. 상품마다 `INSERT … VALUES (?, ?, ?) AS computed ON DUPLICATE KEY UPDATE score = computed.score`다. 저장된 점수에 더하지 않고 구한 값으로 덮는다 |
| chunk 크기 | `WeeklyRankingScoreProperties` | 속성 `weekly-ranking.score.chunk-size`. `application.yml`이 100으로 둔다. 1보다 작으면 시작할 때 거절한다 |

- 라이터의 행 별칭(`AS computed`)은 `VALUES(score)`를 대신한다. 그 함수는 MySQL 8.0.20부터 deprecated이고, 테스트의 컨테이너는 MySQL 8.4다.
- 리더는 운영 코드의 컴포넌트이고 job 설정의 빌더 `@Bean`이 아니다. Batch 6.0.6의 `JdbcCursorItemReader`에 `(DataSource, sql, RowMapper)` 생성자가 있어, 프로세서·라이터와 함께 `step` 아래에 둔다.
- 리더는 드라이버의 fetch size를 기본값으로 둔다. Connector/J는 첫 읽기 전에 한 스냅샷의 상품 ID를 모두 받는다. 그 목록은 상품의 수로 묶인다. 모든 입력을 흘려 읽는 고정의 체크섬(5.1절)과 다르다.
- 다시 시작하면 리더는 같은 질의를 다시 실행하고, 저장된 `scoreProductReader.read.count`만큼 건너뛴다. 스냅샷 입력은 고정한 뒤 바뀌지 않으므로 같은 자리다.
- 빈 스냅샷은 읽은 수 0, 쓴 수 0으로 `COMPLETED`다.

### 12.2 점수 표와 인덱스

| 표 | 컬럼 | 키 |
| --- | --- | --- |
| `weekly_ranking_score`(새 표) | `snapshot_id`(`VARCHAR(100)`), `product_id`(`BIGINT`), `score`(`BIGINT`) | 기본 키 `(snapshot_id, product_id)` |
| `weekly_ranking_snapshot_input`(인덱스 추가) | 그대로 | 보조 인덱스 `IDX_SNAPSHOT_INPUT_ON_SNAPSHOT_ID_AND_PRODUCT_ID_AND_QUANTITY (snapshot_id, product_id, quantity)` |

- 점수는 스냅샷과 상품 한 쌍에 하나다. 기본 키가 그것을 지켜, 둘째 쓰기는 덮고 겹치지 않는다. 점수 표도 자연 키이고 엔티티가 아니다(ADR 0038). 어느 실행이 썼는지의 컬럼은 없다. 재시작이 무엇을 처리했는지는 Batch 메타데이터와 테스트의 spy가 보인다(12.6절).
- 보조 인덱스는 리더의 읽기와 프로세서의 더하기에 인덱스만으로 답하려고 둔다. 기본 키 `(snapshot_id, order_id, product_id)`로는 둘 다 그 스냅샷의 입력을 모두 훑는다. 이것은 명세의 판단이고 `EXPLAIN`으로 확인하지 않았다(12.5절). 테스트는 인덱스가 이름대로, 세 컬럼의 순서대로 있는지를 `information_schema.statistics`에서 읽는다.
- `CODING_STANDARDS.md`대로 이름을 지으면 `IDX_WEEKLY_RANKING_SNAPSHOT_INPUT_ON_SNAPSHOT_ID_AND_PRODUCT_ID_AND_QUANTITY`, 76자로 MySQL의 64자를 넘는다. 그래서 이 단계가 표준에 규칙을 더했다. 이름이 64자를 넘을 때만 표의 부분을 용어집의 짧은 이름(스냅샷 입력 → `SNAPSHOT_INPUT`)으로 줄이고, 컬럼은 줄이지 않는다.
- 인덱스는 스키마 스크립트의 `CREATE TABLE` 안에 적는다. `CREATE TABLE IF NOT EXISTS`는 이미 있는 표에 인덱스를 더하지 않고, MySQL에는 `CREATE INDEX IF NOT EXISTS`가 없다. 그래서 1단계의 표가 남은 로컬 DB에서는 그 표를 손으로 지우고 다시 띄운다. 랭킹의 데이터를 보존하는 환경이 아직 없으므로, ADR 0038이 Flyway를 들일 때로 적은 "보존해야 할 데이터가 든 표의 첫 `ALTER`"는 아직 아니다.

### 12.3 스냅샷의 상태와 재시작

- 스냅샷의 상태는 `FROZEN` 그대로다. `SCORED` 같은 상태를 쓰지 않는다. 그 쓰기는 chunk의 커밋 밖에 놓여, 점수와 상태가 서로 다른 것을 말하는 틈이 생긴다. step이 끝났다는 것은 Batch 메타데이터가 남기고, 다음 브랜치의 게시가 점수의 개수를 그 스냅샷의 상품의 개수와 견준다.
- `score`에서 실패한 실행을 같은 세 값으로 다시 실행하면 같은 `JobInstance`의 새 `JobExecution`이다. Batch는 `COMPLETED`인 `freeze`를 건너뛴다. `freeze`에 `allowStartIfComplete`를 주지 않는다. 그래서 스냅샷과 그 입력, 개수, 체크섬, 고정 시각은 첫 실행의 것 그대로이고, 두 실행 사이에 기록된 구매 활동은 점수에 들지 않는다. 1단계가 열어 둔 "끝난 고정은 다시 실행되지 않는다"(8절)를 이것으로 닫는다.
- 다시 실행한 `score`는 저장된 읽은 수 뒤의 상품만 처리한다. 커밋하지 않은 chunk의 점수는 그 chunk의 읽은 수와 함께 되돌아간다.
- probe의 네 사실(6절)을 진짜 job에서 다시 봤다. `chunk(int)`는 그대로 `ChunkOrientedStepBuilder`를 돌려주고, 리더 `scoreProductReader`의 저장 키는 `scoreProductReader.read.count`다. 같은 인스턴스, 새 실행, 저장된 읽은 수, 남은 상품만 처리가 모두 그대로다. probe와 그 job은 바꾸지 않았다. 과제 3절의 증거로 그대로 남는다.

### 12.4 고르지 않은 것

- **keyset paging 리더.** probe가 Batch 6에서 확인한 것이 cursor 리더이고, 과제의 확인도 `read.count`다. 스냅샷 입력이 바뀌지 않으므로 cursor의 건너뛰기도 같은 자리에 닿는다.
- **`GROUP BY` 리더.** 리더가 상품과 더한 값을 함께 읽으면 상품마다의 질의가 없어진다. 그러나 프로세서가 비고 과제 4절의 꼴에서 벗어난다.
- **`SCORED` 상태.** 12.3절.
- **어느 실행이 썼는지의 컬럼.** 테스트만을 위한 컬럼이다. 재시작이 무엇을 처리했는지는 Batch 메타데이터의 읽은 수·쓴 수와 spy가 센 호출이 보인다.
- **인덱스를 위한 Flyway.** 12.2절.
- **운영 코드의 실패 스위치.** 실패는 테스트의 spy가 만든다(12.6절).

### 12.5 한계

- **프로세서는 상품마다 질의 하나를 실행한다.** 과제 4절의 꼴이다. 한 chunk가 상품 100개면 질의도 100번이다.
- **chunk 크기의 기본값 100은 재지 않았다.** 처리량은 주장하지 않는다. 테스트의 스냅샷에는 상품이 셋이다.
- **보조 인덱스가 두 질의에 인덱스만으로 답한다는 것은 `EXPLAIN`으로 확인하지 않았다.** 테스트는 인덱스가 있음만 본다.
- **인덱스는 이미 있는 표에 저절로 생기지 않는다(12.2절).** `local`, `test` 밖에서는 스키마 스크립트와 함께 손으로 적용한다.
- **이 브랜치에서 끝난 `JobInstance`는 `publish`가 더해진 뒤에 이어 실행할 수 없다.** 1단계와 같은 한계다(8절). 같은 랭킹 기간은 새 `snapshotId`와 차수로 다시 고정한다.
- **과제 6절의 "앞선 게시가 그대로다"와 일곱째 단계(따로 격리한 깨끗한 실행과 결과 체크섬을 견주기)는 이 단계에 없다.** 순위와 게시 포인터가 있어야 하므로 3단계의 몫이다.

### 12.6 장면

9절의 방식 그대로다. 테스트 사이에 랭킹의 세 표와 `purchase_activity`를 비운다. `WeeklyRankingFreezeTest`는 job이 `score`까지 실행된 뒤에도 장면을 바꾸지 않고 지나간다. 점수 표를 비우는 일을 더하고 도우미를 `WeeklyRankingJobs`로 옮겼을 뿐이며, 실패한 고정의 장면에서 `score`는 실행되지 않는다. (2026-11-18 명세 246의 닫는 검토: 두 클래스가 저마다 두던 점수와 스냅샷 입력을 읽는 도우미도 `WeeklyRankingJobs`로 옮겼다. 입력을 쓴 뒤 실패하는 고정의 장면이 실행된 step이 `freeze` 하나뿐임을 스스로 단언한다. 전에는 충돌 장면들의 `single()`이 이를 보였다. 기대를 `freeze`와 `score`로 바꾼 커밋하지 않은 수정에서 그 단언만 붉었다(`["freeze"]`). 되돌렸다.)

| 이음매 | 클래스 | 장면 |
| --- | --- | --- |
| `weeklyRanking` job | `WeeklyRankingScoreTest` | 점수, 빈 랭킹 기간, 점수 바꾸기, 두 스냅샷, 상태, 인덱스. 6개 |
| `weeklyRanking` job, chunk 크기 2 | `WeeklyRankingJobRestartTest` | 실패와 재시작. 6개 |

`WeeklyRankingScoreTest`의 장면은 이렇다.

- 과제의 주를 실행하면 `COMPLETED`이고, 점수 표 전체에 상품 7이 5, 상품 8이 1, 상품 9가 5, 그 셋뿐이다. 주문 42와 43의 상품 7이 점수 하나로 더해진다.
- 구매 활동이 없는 랭킹 기간은 `COMPLETED`이고 점수가 없다. `score` step 실행은 읽은 수 0, 쓴 수 0이다.
- 그 스냅샷 ID로 상품 7에 99를 먼저 넣어 두고 실행하면 `COMPLETED`이고 5가 된다. 8과 9는 1과 5다.
- 같은 랭킹 기간을 다른 스냅샷 ID와 차수로 한 번 더 실행하면 두 스냅샷이 저마다 5, 1, 5를 가진다.
- 끝난 실행 뒤 스냅샷의 상태는 `FROZEN`이다.
- 스냅샷 입력의 보조 인덱스가 이름대로, 세 컬럼의 순서대로 있다. 그 표의 유일하지 않은 인덱스를 모두 읽으므로, 둘째 인덱스나 바뀐 순서도 드러난다.

`WeeklyRankingJobRestartTest`는 `WeeklyRankingScoreStore`를 springmockk의 `@MockkSpyBean`으로 감싸 정한 상품에서 실패시킨다. 1단계의 `digestInputs` spy와 같은 방식이다. chunk 크기가 2라서 상품 7과 8이 첫 chunk, 9가 둘째 chunk다. 첫 실행과 재시작 사이에 Batch 메타데이터와 랭킹의 표를 비우지 않는다. 그 사이에 spy의 실패를 거두되 기록한 호출은 남기고, 테스트의 시계를 하루 옮기고, 그 랭킹 기간에 주문 45(상품 9, 수량 4)를 기록한다.

- 상품 9 앞에서 실패한 첫 실행은 `FAILED`다. 점수는 상품 7과 8의 둘이고, DB에서 다시 읽은 `score` step의 `ExecutionContext`에 `scoreProductReader.read.count`가 2이며, 스냅샷은 `FROZEN`이다.
- 같은 세 값으로 다시 실행하면 같은 `JobInstance`, 새 `JobExecution`, `COMPLETED`이고 점수는 5, 1, 5다. 재시작의 `score` step 실행은 읽은 수 1, 쓴 수 1이고, 두 실행에 걸쳐 상품 7과 8의 수량은 한 번씩만 더해졌다. (2026-11-18 명세 246의 닫는 검토: 상품 9는 두 번 더해졌다. 첫 실행에서 던진 한 번과 재시작의 한 번이다. 이야기 19의 "상품마다 더한 횟수"를 다 세려고 더했다. 읽은 수·쓴 수는 프로세서를 다시 부르는 재시도를 보지 못한다. 기대를 한 번으로 바꾼 커밋하지 않은 수정에서 그 단언만 붉었다(`2 matching calls found, but needs exactly 1 calls`). 되돌렸다.)
- 재시작은 다시 고정하지 않았다. 그 인스턴스의 `freeze` step 실행은 하나이고, 재시작은 `COMPLETED`이며, 스냅샷의 고정 시각·개수·체크섬과 스냅샷 입력의 행이 첫 실행 뒤와 같다. 둘째 고정은 스냅샷을 바꾸지 못하고 충돌로 실패하므로(5.1절), 같은 행만으로는 건너뛴 고정과 거절된 고정을 가르지 못한다. 하나뿐인 step 실행과 끝난 재시작이 가른다.
- 상품 8에서, 곧 첫 chunk 안에서 실패하면 `FAILED`이고 점수가 하나도 없다. 저장된 `score` step 실행은 있으나, 그 `ExecutionContext`에 `scoreProductReader.read.count`가 없다.
- 그 실행을 다시 실행하면 세 상품을 모두 처리한다. 읽은 수 3, 쓴 수 3이고 점수는 5, 1, 5다.
- 상품 9의 점수를 쓴 뒤, 그 chunk가 커밋되기 전에 실패하면 9의 점수는 남지 않고 저장된 읽은 수는 2다. spy가 `saveScores`의 원래 메서드를 부른 뒤 던진다.

### 12.7 붉은 확인

커밋하지 않은 로컬 수정으로 돌리고 되돌렸다(2026-11-18). 티켓마다의 기록은 글감에 있다.

| 수정 | 붉어진 장면 |
| --- | --- |
| 라이터를 `score = weekly_ranking_score.score + computed.score`로 | 틀린 점수의 장면 하나. 상품 7이 104다 |
| 더하기에서 `snapshot_id`의 조건을 빼기 | 두 스냅샷의 장면 하나. 둘째 스냅샷의 점수에 첫 스냅샷의 입력이 든다 |
| 리더에 `setSaveState(false)` | 재시작 테스트의 셋. 상품 9 앞의 실패와 쓰기 뒤의 실패에서 저장된 읽은 수가 없고, 재시작이 3을 읽고 3을 쓴다. 그 위의 점수 5, 1, 5는 지나간다. 구한 값으로 덮는 저장이 가린 재처리를 메타데이터가 잡는다. 더한 횟수의 단언을 읽은 수·쓴 수의 단언 앞으로 옮겨 다시 돌리면, 상품 7이 두 번 더해져 그 단언도 붉다 |
| `score` step을 `ResourcelessTransactionManager`로 | 쓰기 뒤의 실패 장면 하나. 실패한 실행에 상품 9의 점수 5가 남고, 저장된 읽은 수는 2다 |
| 프로세서가 `purchase_activity`에서 더하게 | 재시작 뒤 점수를 보는 두 장면. 상품 9가 9다. 고정된 5에 고정 뒤의 주문 45의 4가 더해졌다 |

### 12.8 끝의 확인

```
scripts/final-check --app commerce-batch
```

- 2026-11-18 `8563adad`의 깨끗한 트리에서 돌렸다(08:59:42Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다(2 s).
- commerce-batch는 7개 클래스, 45개 테스트, 실패 0, 오류 0, 건너뜀 0이다(26 s). 1단계의 끝의 확인(9.3절)이 5개 클래스와 33개 테스트였으므로, 이 단계가 2개 클래스(`WeeklyRankingScoreTest`, `WeeklyRankingJobRestartTest`)와 12개 테스트를 더했다.
- 이 브랜치는 commerce-streamer를 바꾸지 않았으므로 돌리지 않았다.
- `8563adad`의 코드는 `fda30058`의 것이고, 그 뒤로는 문서만 바뀌었다.
- (2026-11-18 [명세 246](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/246)의 닫는 검토) 검토의 고침(`184ae7f9`부터 `d7deb546`까지)이 테스트와 운영 코드를 바꿨으므로 `b63678a2`의 깨끗한 트리에서 다시 돌렸다(09:45:43Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했다(1 s). commerce-batch는 7개 클래스, 45개 테스트, 실패 0, 오류 0, 건너뜀 0이다(22 s). 검토가 더한 단언 둘은 있던 테스트의 줄이라 수는 그대로다. 이것이 2단계의 끝의 확인이다. 위의 `8563adad`의 실행은 앞의 확인이다. `b63678a2` 뒤로는 문서만 바뀌었다.

### 12.9 명세와 다른 것

2026-11-18 구현을 마치고 명세 246과 견준 것이다.

| 명세 | 구현 | 어디서 |
| --- | --- | --- |
| `score` step을 `ResourcelessTransactionManager`로 바꾸면 "저장된 읽은 수나 chunk 안의 실패 장면이 붉다"(붉은 확인) | 더하기에서 실패하는 장면은 하나도 붉지 않다. 실패가 늘 chunk의 쓰기 앞이라 써 놓고 커밋하지 않은 점수가 없고, Batch는 어느 쪽이든 메타데이터를 제 저장소로 남긴다. 쓰기 뒤에 실패하는 장면을 더했고, 그 장면이 붉다(12.7절) | 249 |
| 리더에 `saveState(false)`를 주면 재시작의 읽은 수·쓴 수와 더한 횟수가 붉다(붉은 확인) | 커밋한 장면은 읽은 수·쓴 수의 단언에서 멈춰, 그 뒤의 더한 횟수까지 가지 않는다. 더한 횟수가 붉은 것은 단언의 차례를 바꾼 커밋하지 않은 수정으로 따로 봤다(12.7절) | 250 |
| 실패는 "한 상품의 수량을 더하는" 메서드의 spy로 만든다 | 쓰기 뒤의 실패 장면은 같은 spy 빈의 `saveScores`로 만든다. 더하기의 이음매로는 이야기 15(한 chunk의 점수와 읽은 수가 함께 커밋된다)를 보일 수 없다. 운영 코드에 실패 스위치는 여전히 없다 | 249 |
| 그 메서드의 이름은 구현에서 정한다 | `WeeklyRankingScoreStore.sumQuantity` | 247 |
| chunk 안의 실패에서 저장된 읽은 수는 없거나 0이다. 어느 쪽인지는 구현에서 확인해 적는다 | 없다. 저장된 `score` step 실행은 있으나 그 `ExecutionContext`에 키가 없다 | 249 |
| 틀린 점수의 장면은 248의 몫(명세가 아니라 티켓의 나눔) | 247의 커밋에 이미 있었다. 248은 그 장면이 실행의 `COMPLETED`를 먼저 단언하게 했다 | 247, 248 |
| 인덱스에 이름을 붙인다(이름은 정하지 않았다) | 표준대로의 이름이 MySQL의 64자를 넘어, 그때만 표의 부분을 용어집의 짧은 이름으로 줄이는 규칙을 `CODING_STANDARDS.md`에 더했다(12.2절) | 247 |

### 12.10 3단계로 넘기는 것

- 순위, 게시, 게시 포인터, 결과 체크섬, `GET /api/v1/rankings/weekly`, 차수 사이의 순서.
- 과제 6절의 "앞선 게시가 그대로다"와 일곱째 단계(12.5절).
- 점수의 단계가 끝났다는 표시는 Batch 메타데이터뿐이다. 게시는 점수의 개수를 그 스냅샷의 상품의 개수와 견준다(12.3절).
- `publish`가 실패한 뒤 다시 실행하면 `freeze`와 `score`가 모두 건너뛰어지는지. 이 단계가 `freeze`에 대해 보인 것(12.6절)을 둘째 step에도 본다.
- 이 브랜치에서 끝난 `JobInstance`는 이어 실행할 수 없다(12.5절).
