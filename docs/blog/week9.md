# 주간 랭킹 배치의 선택들: 엔티티 없는 JDBC, cursor Reader, read.count

## 들어가며

9주차에는 Spring Batch 6으로 주간 랭킹을 만들었다. 주간 랭킹 배치 job `weeklyRanking`은 세 step으로 구성된다. `freeze`가 한 랭킹 기간의 구매 활동을 스냅샷으로 고정하고, `score`가 스냅샷의 상품마다 점수를 내고, `publish`가 순위를 매겨 게시한다.

실무에서 Spring Batch를 사용해 본 경험이 부족해서인지 구현하면서 몇몇 낯선 선택이 있었다. JPA가 있는데 엔티티가 없다. Reader는 흔히 권하는 paging이 아니라 cursor다. 재시작은 숫자 하나에 기댄다.

## 왜 JPA 엔티티가 아닌가

commerce-batch의 클래스패스에는 JPA와 QueryDSL이 이미 있다. 그런데도 랭킹의 테이블은 엔티티가 아니고, SQL은 `JdbcClient`에 문자열로 적었다. 바뀌지 않는 행에는 엔티티가 주는 것이 필요 없고, 엔티티가 요구하는 것은 이 테이블에 맞지 않기 때문이다.

엔티티는 읽어서 고치고 다시 저장하는 대상에 어울린다. 스냅샷은 한 번 쓰고 읽기만 한다. 그래서 일의 단위가 행이 아니라 집합이다. `WeeklyRankingSnapshotStore`의 네 메서드를 JPA로 옮긴다고 해 보면 드러난다.

| 메서드 | JPA·QueryDSL로 쓰면 | 맞는가 |
| --- | --- | --- |
| `findConflicting` | 파생 쿼리나 `where(a.or(b.and(c)))` | 어느 쪽이든 좋다 |
| `insertFrozenSnapshot` | `repository.save(snapshot)` | 어느 쪽이든 좋다 |
| `copyInputs` | HQL `INSERT … SELECT`, QueryDSL `JPAInsertClause` | 되지만 어색하다 |
| `digestInputs` | `getResultStream()`이나 `StatelessSession` 스크롤 | 되지만 ORM과 싸운다 |

앞의 둘은 행 하나를 다루는 일이라 JPA가 잘하는 자리다. 무게는 뒤의 둘에 있다.

- **`copyInputs`는 문장 하나여야 한다.** 고정의 약속은 "모든 스냅샷 입력이 한 번의 읽기에서 나온다"이고, `INSERT … SELECT` 한 문장이 그것을 준다. 엔티티를 읽어서 다시 저장하는 루프는 문장 여럿이 된다. HQL로 한 문장을 쓸 수는 있지만 두 테이블이 모두 엔티티여야 하고, 그 벌크 문장은 영속성 컨텍스트와 감사 컬럼을 어차피 지나친다.
- **`digestInputs`는 한 주의 행을 메모리에 쌓지 않고 흘려야 한다.** JPA로는 DTO로 투영하거나 영속성 컨텍스트를 비워 가며 읽어야 하고, MySQL의 스트리밍 힌트를 Hibernate 너머로 넘겨야 한다. JDBC에서는 `withFetchSize(Int.MIN_VALUE)` 한 줄이다.

엔티티가 되면 따라오는 것들도 바뀌지 않는 테이블과 어긋난다.

- 이 저장소의 엔티티는 모두 `BaseEntity`를 상속하고 논리 삭제한다. 고치지도 지우지도 않는 행에는 수정 시각과 삭제 표시가 가리킬 것이 없다.
- 공용 `jpa.yml`은 `local`, `test`에서 `ddl-auto: create`다. 랭킹의 테이블이 엔티티면 시작할 때마다 지워져, 다시 시작하는 실행이 앞선 실행의 행을 잃는다. `purchase_activity`를 엔티티로 잡으면 배치를 띄울 때 스트리머의 테이블이 비워진다.
- `purchase_activity`는 다른 맥락(스트리머)의 테이블이다. 배치는 계약의 네 컬럼만 SQL로 읽는다. 엔티티로 잡으면 남의 테이블을 한 번 더 매핑하게 된다.

실무에서도 한 가지만 쓰지 않고 일의 모양에 따라 섞는다. 애그리거트를 읽어 고치는 트랜잭션 코드는 JPA(와 QueryDSL)로, 배치·집합 연산·리포팅은 SQL 수준의 도구(`JdbcTemplate`/`JdbcClient`, jOOQ, MyBatis)로 쓴다. commerce-api가 앞쪽이고 commerce-batch가 뒤쪽이다. 대가는 SQL이 문자열이라는 것이다. 컬럼 이름이 어긋나도 컴파일은 통과하고, 실제 MySQL에서 job을 돌리는 테스트가 잡는다.

엔티티가 없으니 `score` step의 Reader도 JPA Reader가 될 수 없다. 남는 것은 JDBC의 두 Reader, cursor와 paging이다.

## Reader는 왜 cursor인가

`ScoreProductReader`는 `JdbcCursorItemReader`를 상속하고, 스냅샷의 상품을 상품 ID 순으로 한 번씩 읽는다.

```kotlin
@StepScope
@Component
class ScoreProductReader(
    dataSource: DataSource,
    @Value("#{jobParameters['snapshotId']}") snapshotId: String,
) : JdbcCursorItemReader<Long>(dataSource, SQL, RowMapper { rs, _ -> rs.getLong("product_id") }) {
    init {
        setName("scoreProductReader")
        setPreparedStatementSetter { statement -> statement.setString(1, snapshotId) }
    }
    // SELECT DISTINCT product_id FROM weekly_ranking_snapshot_input
    // WHERE snapshot_id = ? ORDER BY product_id
}
```

cursor Reader의 재시작은 단순하다. 같은 질의를 다시 실행하고, 앞선 실행이 읽은 수만큼 행을 건너뛴다. 데이터와 순서가 그대로일 때만 같은 자리에 닿는 방법이다. 보통의 테이블에서는 위험한 전제지만, 스냅샷 입력은 고정한 뒤 바뀌지 않으니 여기서는 그냥 참이다. 그것이 cursor를 고를 수 있었던 까닭이고, 고른 근거는 둘 더 있다.

- **Batch 6에서 이미 확인한 Reader다.** 진짜 job을 짜기 전에 probe job으로 재시작을 시험했다. cursor Reader가 chunk마다 `<이름>.read.count`를 저장하고, 재시작이 남은 항목만 처리한다는 것을 Batch 6.0.6에서 봤다.
- **과제가 `read.count`를 확인한다.** 재시작 장면은 실패한 실행의 `scoreProductReader.read.count`가 2인지를 본다.

검토하고 고르지 않은 것은 keyset paging이다. 마지막으로 읽은 키 뒤에서 이어 읽으므로 데이터가 바뀌어도 정확하다. 여기서는 데이터가 바뀌지 않으니 그 장점이 쓰이지 않는다.

| Reader | 재시작 방법 | 여기서의 득실 |
| --- | --- | --- |
| `JdbcCursorItemReader` (고름) | 질의를 다시 실행하고 `read.count`만큼 건너뛴다 | 질의 한 번. 바뀌지 않는 데이터가 전제다. 스레드 하나만 쓴다 |
| keyset paging (고르지 않음) | 마지막 키 뒤에서 잇는다 | 데이터가 바뀌어도 정확하지만, 여기서는 바뀌지 않는다 |
| `JdbcPagingItemReader` | 저장된 페이지 자리에서 잇는다 | 페이지마다 질의하고 매번 `DISTINCT`를 다시 한다 |
| JPA Reader | Reader마다 다르다 | 엔티티가 필요하다. 위에서 두지 않기로 했다 |

cursor의 비용도 있다. 건너뛰기는 선형이라, 상품 백만 개를 처리한 뒤 재시작하면 백만 행을 읽고 버린다. 그리고 MySQL에서는 이름과 달리 서버 커서가 아니다. fetch size를 주지 않으면 Connector/J가 결과 전체를 한 번에 메모리로 가져온다. 여기서는 상품마다 `Long` 하나라 감당할 만하다.

여기까지는 이 테이블의 사정에서 나온 근거다. 한 걸음 물러나 두 Reader의 일반적인 득실을 놓고 이 선택을 다시 본다.

## cursor냐 paging이냐

RDB를 읽는 Reader는 두 갈래다. cursor Reader는 질의를 한 번 실행하고 결과를 한 건씩 내준다. paging Reader는 페이지마다 질의를 다시 한다.

| | cursor | paging |
| --- | --- | --- |
| 읽는 방식 | 질의 한 번, 커서를 연 채 한 건씩 | 페이지마다 질의 |
| 장점 | 메모리가 적게 든다. 한 번의 읽기라 일관된다 | 오래 열린 커서가 없다. 재시작이 쉽다 |
| 단점 | 커서와 커넥션을 오래 잡는다. 재시작이 까다롭다 | 페이지 사이에 데이터가 바뀌면 흐트러진다. OFFSET은 깊어질수록 느리다 |

흔한 기준은 이렇다. 데이터가 작고(수만~수십만 건) 일관성이 중요하면 cursor다. 데이터가 크거나, 컨테이너에서 재시작을 이어가야 하거나, 다른 세션이 자주 바꾸는 테이블이면 paging이다. 큰 데이터에서는 paging이 사실상 표준이고, OFFSET 대신 keyset(마지막으로 읽은 키 뒤를 `WHERE id > :lastId`로 읽기)을 쓴다.

이 기준을 우리 Reader에 하나씩 대 본다.

- **cursor의 "재시작이 까다롭다"는 여기서 풀린다.** 까다로운 것은 건너뛴 자리가 같다는 전제다. 스냅샷 입력이 바뀌지 않으니 그 전제가 항상 참이다. paging의 가장 큰 장점이 이 테이블에서는 장점이 아니다.
- **paging의 "페이지 사이에 바뀐다"도 일어나지 않는다.** 같은 이유로 cursor의 일관성도 여기서는 더 주는 것이 없다. 정확함만 보면 두 Reader가 비긴다.
- **그래서 남는 것은 단순함이다.** cursor는 `DISTINCT` 질의를 한 번 실행한다. paging은 페이지마다 다시 실행한다. 그리고 cursor는 probe로 이미 재시작을 확인한 Reader다.
- **"커서와 커넥션을 오래 잡는다"는 작은 비용으로 남는다.** Reader는 step이 도는 동안 커넥션 하나를 잡는다. 상품 수가 작아 step이 짧고, MySQL에서는 결과를 한 번에 가져와 서버에 열린 커서가 없다.

표의 두 줄은 코드를 열어 보니 그대로가 아니었다.

- **cursor의 "메모리가 적게 든다"는 드라이버 설정에 달렸다.** Connector/J는 fetch size가 `Integer.MIN_VALUE`일 때 한 행씩 흘리고, 그 밖의 값은 `useCursorFetch=true`가 있어야 듣는다. 그렇지 않으면 결과 전체를 메모리에 올린다. `fetchSize(1000)`은 MySQL에서 스트리밍을 켜지 않는다.
- **`JdbcPagingItemReader`는 OFFSET을 쓰지 않는다.** Batch 6의 클래스는 정렬 키의 마지막 값(`startAfterValues`)을 들고 다음 페이지를 그 뒤에서 읽는다. 이미 keyset이다. OFFSET의 비용은 `setFirstResult`로 페이지를 넘기는 JPA Reader의 이야기다.

답이 바뀌는 조건도 이 비교에서 나온다. 상품이 수백만으로 늘면 건너뛰기와 메모리가 부담이 된다. step을 여러 스레드로 돌리면 cursor Reader를 쓸 수 없다. 읽는 테이블이 바뀌면 건너뛰기가 틀린 자리에 닿는다. 셋 중 하나면 `JdbcPagingItemReader`로 옮긴다.

지금은 셋 다 아니다. 그러면 cursor Reader는 어디까지 읽었는지를 어떻게 기억하는가.

## read.count 하나로 재시작하기

cursor Reader가 재시작을 위해 남기는 것은 숫자 하나, 읽은 행의 수다. 저장되는 곳은 step의 `ExecutionContext`이고, Spring Batch가 그것을 `BATCH_STEP_EXECUTION_CONTEXT` 테이블에 JSON으로 적는다.

1. Reader가 물려받은 카운터가 `read()`마다 1씩 는다.
2. chunk가 끝나면 step이 Reader의 `update(executionContext)`를 불러, 카운터를 `scoreProductReader.read.count`라는 키로 담는다. 키의 앞부분은 `setName()`으로 준 이름이다.
3. step이 `JobRepository`에 그 컨텍스트를 저장하게 한다.

숫자 하나로 충분하려면 두 가지가 맞아야 한다. 숫자가 처리한 양과 어긋나지 않아야 하고, 그 숫자가 가리키는 자리가 항상 같아야 한다.

**숫자는 처리한 양과 함께 커밋된다.** 2와 3은 chunk의 트랜잭션 안에서, 커밋 전에 일어난다. chunk 하나가 트랜잭션 하나라는 규칙이 여기서 일한다. 상품 7, 8, 9를 chunk 크기 2로 처리하다가 9 앞에서 실패하는 장면을 보자.

| chunk | 읽은 상품 | 결과 | 저장된 `read.count` |
| --- | --- | --- | --- |
| 1 | 7, 8 | 점수를 쓰고 커밋 | 2 |
| 2 | 9 | 실패, 롤백 | 그대로 2 |

메모리의 카운터는 3까지 갔지만 그 값은 chunk와 함께 되돌아갔다. 저장된 값은 언제나 점수가 커밋된 상품의 수와 같다. 다시 시작하면 Spring Batch가 실패한 실행의 `ExecutionContext`를 새 step 실행에 넘겨주고, Reader의 `open()`이 같은 질의를 다시 실행한 뒤 두 행을 건너뛴다. 다음 `read()`가 상품 9다.

**숫자가 가리키는 자리는 항상 같다.** "세 번째 행"이 어느 실행에서나 같은 상품이어야 하고, 세 조건이 그것을 보장한다.

- **순서가 정해져 있다.** 질의에 `ORDER BY product_id`가 있다.
- **데이터가 바뀌지 않는다.** 스냅샷 입력은 고정한 뒤 그대로다.
- **스레드가 하나다.** 카운터가 행의 자리와 정확히 같다.

하나라도 깨지면 두 행을 건너뛴 자리가 다른 상품일 수 있다. cursor의 재시작이 까다롭다는 말이 가리키는 것이 이것이고, keyset paging이 필요한 경우가 그때다.

테스트도 같은 규칙을 두 방향에서 보여 준다. 첫 chunk 안에서(상품 8에서) 실패하면 `read.count`가 아예 없다. 커밋된 chunk가 없었기 때문이다. Reader에 `saveState(false)`를 주면 재시작이 7과 8을 다시 쓴다.

그런데 어느 클래스에도 `@Transactional`은 없다. 점수와 숫자를 함께 커밋하는 그 트랜잭션은 누가 여는가.

## 그 트랜잭션은 step이 연다

트랜잭션을 여는 것은 step이다. step을 만들 때 트랜잭션 매니저를 넘기고, step이 일의 단위마다 트랜잭션을 열고 닫는다.

```kotlin
// score: chunk 하나가 트랜잭션 하나
StepBuilder(SCORE_STEP_NAME, jobRepository)
    .chunk<Long, ProductScore>(properties.chunkSize)
    .transactionManager(transactionManager)

// freeze: execute() 한 번이 트랜잭션 하나
StepBuilder(FREEZE_STEP_NAME, jobRepository)
    .tasklet(freezeTasklet, transactionManager)
    .transactionAttribute(readCommitted())
```

정상으로 끝나면 커밋하고 예외가 나면 롤백한다. `JdbcClient`는 같은 `DataSource`에서 스레드에 묶인 커넥션을 받으므로 스토어의 SQL이 모두 그 트랜잭션에 든다. Spring Batch가 이렇게 하는 까닭은 앞 절에서 본 그대로다. 업무 데이터와 step의 진행 기록이 같은 트랜잭션에서 커밋되어야, 다시 시작했을 때 둘이 어긋나지 않는다.

tasklet도 같은 규칙을 따른다. 단위가 chunk 하나에서 `execute()` 한 번으로 바뀔 뿐이다. `freeze`가 tasklet인 까닭이 여기에 있다. 고정은 전부 아니면 전무여야 한다. 스냅샷 입력을 옮겨 담고, 체크섬을 계산하고, 스냅샷 행을 넣는 세 단계가 함께 커밋된다. 마지막 `INSERT`가 유일 키에 걸려도 앞에서 옮겨 담은 입력이 함께 되돌아가, 다시 실행하기 전에 치울 것이 없다. chunk로 나누었다면 일부만 커밋된 스냅샷이 생길 수 있다.

두 step의 단위가 다른 것도 같은 사실에서 나온다. 스냅샷이 바뀌지 않으려면 한 번에 온전히 생겨야 하니 `freeze`는 트랜잭션 하나다. 스냅샷이 바뀌지 않으니 `score`는 여러 트랜잭션으로 나누어도, 중간에서 이어도 같은 결과가 나온다.

`freeze`의 격리 수준은 MySQL의 기본인 REPEATABLE READ 대신 READ COMMITTED로 낮췄다. REPEATABLE READ에서는 `INSERT … SELECT`가 읽는 `purchase_activity`의 범위에 잠금이 걸려, 한 주를 옮겨 담는 동안 그 주의 구매 활동을 기록하던 스트리머가 멈출 수 있다. READ COMMITTED에서도 한 문장은 한 번의 일관된 읽기라서 고정의 뜻은 그대로다.

Reader의 상태는 `read.count`가, 트랜잭션은 step이 맡는다. 그러면 Processor와 Writer에는 무엇이 남는가.

## Processor와 Writer에는 왜 상속이 없는가

남는 것이 거의 없다. 그래서 Reader는 프레임워크의 클래스를 상속하는데, `ScoreProcessor`와 `ScoreWriter`는 `ItemProcessor`와 `ItemWriter`를 구현할 뿐이다.

- **Reader에는 수명이 있다.** 문장을 열고 닫고, 행을 세고, `read.count`를 저장하고 되살린다. 손으로 짜면 프레임워크를 다시 만드는 일이다.
- **Processor와 Writer는 메서드 하나다.** Processor는 상품 ID를 점수로 바꾸고, Writer는 chunk를 스토어에 넘긴다. chunk 사이에 들고 가는 것이 없으니 재시작을 위해 저장할 것도 없다.
- **SQL은 `WeeklyRankingScoreStore`에 모았다.** 실패 장면의 테스트가 그 스토어의 메서드에 spy를 걸어 실패를 만든다. 운영 코드에 실패 스위치를 두지 않아도 된다.

Writer가 상태 없이도 안전한 것은 저장이 더하기가 아니라 계산한 값 그대로이기 때문이다. chunk의 점수를 한 문장에 한 행씩 담아 보낸다.

```sql
INSERT INTO weekly_ranking_score (snapshot_id, product_id, score)
VALUES (?, ?, ?), (?, ?, ?), … AS computed
ON DUPLICATE KEY UPDATE score = computed.score
```

여기에도 같은 사실이 있다. 스냅샷 입력이 바뀌지 않으니 한 상품의 점수는 몇 번을 구해도 같고, 같은 상품을 두 번 써도 테이블은 그대로다. `read.count`가 첫째 안전망이고 이 문장이 둘째다.

세 클래스를 나란히 놓으면 차이가 하나 더 보인다. 같은 `@Value`인데 Reader에는 그냥 쓰고 Processor와 Writer에는 `@param:Value`라고 썼다. 이 저장소는 컴파일러 옵션 `-Xannotation-default-target=param-property`를 켠다. 그러면 생성자의 `val` 프로퍼티에 붙인 애너테이션이 매개변수와 필드 양쪽에 붙는다. Spring은 필드의 `@Value`도 주입 지점으로 보므로, 생성자로 한 번 받은 값을 필드에 한 번 더 넣으려 한다. `@param:`은 매개변수에만 붙이라는 뜻이다. Reader의 `snapshotId`는 생성할 때만 쓰는 매개변수라 붙을 필드가 없고, 그래서 그냥 쓴다.

## 남긴 비용과 정리

남겨 둔 비용이 하나 있고, 이 글을 쓰다가 고친 비용이 하나 있다. 둘 다 왕복 횟수다.

- **Processor가 상품마다 질의한다.** `ScoreProcessor`는 상품마다 `SUM(quantity)` 질의를 하나씩 보낸다. Reader가 `GROUP BY`로 상품과 더한 값을 함께 읽으면 없어지지만, Processor가 비고 과제가 요구한 Reader·Processor·Writer의 꼴에서 벗어난다. 커버링 인덱스 `(snapshot_id, product_id, quantity)`로 한 번의 질의를 싸게 만드는 데서 멈추었다.
- **Writer는 한 건씩 보내다가 한 문장으로 바꿨다.** 처음의 `saveScores`는 트랜잭션은 하나지만 점수마다 문장을 하나씩 보냈다. chunk 100이면 왕복이 100번이다. 이 글을 쓰다가 알아차려, chunk의 점수를 여러 행의 `INSERT` 한 문장으로 보내게 고쳤다. JDBC 배치는 드라이버가 문장을 고쳐 써야 왕복이 줄지만, 여러 행의 문장은 그 자체로 한 문장이다. 대신 chunk 크기가 문장의 크기도 정한다.

다시 처음의 한 줄로 돌아가면, 스냅샷은 고정한 뒤 바뀌지 않는다. 그래서 엔티티의 수정 시각과 논리 삭제가 필요 없었고, cursor Reader의 건너뛰기가 같은 자리에 닿았고, `read.count` 하나로 재시작이 되었고, Writer가 같은 점수를 다시 써도 달라지는 것이 없었다.

바뀌는 테이블이었다면 답이 달랐을 것이다. Reader는 keyset paging이 되고, 재시작의 정확함은 Writer의 멱등성에 더 기대야 한다. 도구를 고르기 전에 데이터가 바뀌는지부터 묻는 것이 이번 주에 얻은 순서다.

더 자세한 기록은 [주간 랭킹 설계 문서](../design/weekly-ranking.md)와 [ADR 0038](../adr/0038-weekly-ranking-tables-are-not-entities.md)에 있다.
