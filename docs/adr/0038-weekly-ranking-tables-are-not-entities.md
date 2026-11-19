---
status: accepted
date: 2026-11-17
---

# 주간 랭킹의 표는 엔티티가 아니다

9주차 과제는 commerce-batch에 주간 랭킹을 만들게 한다. 첫 단계([명세 237](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237))는 한 랭킹 기간의 구매 활동을 스냅샷으로 고정한다. 뒤의 단계는 그 스냅샷에서 점수를 내고 게시하며, 중간에 실패한 job을 다시 시작해도 한 번에 끝난 실행과 같은 랭킹이 나와야 한다. 다시 시작하는 실행은 앞선 실행이 남긴 행을 찾아야 한다.

commerce-batch는 템플릿 그대로였다. 데모 job 하나와 `jpa.yml`을 들일 뿐 엔티티는 없었다. `jpa.yml`은 `local`, `test`에서 `ddl-auto: create`다. Hibernate는 시작할 때마다 아는 엔티티의 표를 지우고 다시 만든다. 그리고 커머스의 기록에는 두 규칙이 있다. 모든 엔티티는 `BaseEntity`를 상속하고 애그리거트 루트는 논리 삭제한다([ADR 0016](./0016-every-entity-extends-base-entity-and-roots-soft-delete.md)). commerce-api는 provided·required 포트를 둔 헥사고날 구조다([ADR 0013](./0013-commerce-api-follows-splearn-hexagonal-structure.md)).

주간 랭킹은 셋째 맥락이다([`CONTEXT-MAP.md`](../../CONTEXT-MAP.md), 용어는 [`apps/commerce-batch/CONTEXT.md`](../../apps/commerce-batch/CONTEXT.md)). 구매 활동과는 `purchase_activity` 표로만 이어진다. 결정은 넷이다.

1. **commerce-batch에는 JPA 엔티티가 없다. 랭킹의 표에는 `BaseEntity`의 열도 논리 삭제도 없다(ADR 0016과 다르다).** `weekly_ranking_snapshot`의 기본 키는 `snapshot_id`이고 `(period_start, revision)`이 유일 키다. `weekly_ranking_snapshot_input`의 기본 키는 `(snapshot_id, order_id, product_id)`다. 자연 키이고, 대리 키와 `created_at`·`updated_at`·`deleted_at`이 없다. 스냅샷은 고정한 뒤 바뀌지 않고 지우지 않으므로 수정 시각과 삭제 표시가 가리킬 것이 없다. 고정한 시각은 `frozen_at`이 따로 적는다. 엔티티가 없으니 `ddl-auto: create`가 지울 표도 없다. 랭킹의 표가 엔티티면 다시 시작하는 실행이 앞선 실행의 행을 잃고, `purchase_activity`를 엔티티로 잡으면 배치를 띄울 때 스트리머의 표가 비워진다.
2. **표는 시작 스크립트 한 파일이 만든다.** `sql/weekly-ranking-schema.sql`이 `CREATE TABLE IF NOT EXISTS`로 없는 표만 만들고 아무것도 지우지 않는다. `spring.sql.init`이 `local, test` 블록에서 시작할 때마다 그것을 실행한다. Batch 메타데이터의 `initialize-schema: always`와 같은 블록, 같은 종류의 방식이다. 다른 프로필에서는 메타데이터와 마찬가지로 사람이 손으로 적용한다. 뒤의 단계는 이 파일에 표를 더한다. `IF NOT EXISTS`가 필요한 까닭이 있다. Batch의 `schema-mysql.sql`에는 그것이 없지만 Boot의 Batch 초기화는 `continue-on-error`의 기본이 `true`라 넘어가고, `spring.sql.init`의 기본은 `false`라서 이미 있는 표에서 시작이 실패한다.
3. **랭킹의 표와 `purchase_activity`는 `JdbcClient`로 읽고 쓴다.** `purchase_activity`는 계약의 열(`order_id`, `product_id`, `quantity`, `confirmed_at`)과 `deleted_at is null`만 SQL로 읽는다. 시각은 UTC의 `LocalDateTime`으로 묶고 읽으며, 변환은 `JdbcUtcTimes` 한 곳에 있다. Connector/J는 `Instant`, `Timestamp`, `OffsetDateTime`을 JVM의 시간대(Asia/Seoul)로 저장해, Hibernate가 UTC로 쓴 행과 아홉 시간 어긋난다. Hibernate의 `jdbc.time_zone: UTC`는 JDBC를 직접 쓰는 코드에 닿지 않는다.
4. **commerce-batch에는 헥사고날 포트가 없다(ADR 0013과 다르다).** 템플릿의 배치를 따른다. `com.loopers.batch.job.weeklyranking`에 job 설정과 매개변수 검증기를, 그 아래 `step`에 tasklet과 SQL을 든 `WeeklyRankingSnapshotStore`를 둔다. ADR 0013은 웹, 영속성, 밖으로 나가는 HTTP의 어댑터가 여럿이고 슬라이스가 서로를 부르는 commerce-api의 결정이다. 여기에는 DB 하나와 부르는 쪽(step) 하나뿐이라, 포트는 구현이 하나뿐인 인터페이스가 된다. SQL 없이 확인할 규칙(체크섬)은 순수한 코드(`SnapshotInputDigest`)로 떼어 둔다.

## 고르지 않은 것

- **엔티티로 두고 commerce-batch의 `ddl-auto`를 재정의한다(`none`이나 `validate`).** 공용 `jpa.yml`의 값을 앱 하나에서 뒤집으면, 그 값을 믿는 사람이 엔티티를 하나 더할 때 표가 생기지 않거나 반대로 지워진다. 그리고 엔티티가 되면 ADR 0016의 `BaseEntity`와 논리 삭제가 따라온다. 바뀌지 않고 지우지 않는 행에 수정 시각과 삭제 필터를 둘 까닭이 없다. `update`는 지우지 않지만 열을 고치지도 지우지도 않는 반쪽 마이그레이션이다.
- **Flyway.** 이 모듈에는 이미 Batch 메타데이터의 초기화와 `jpa.yml`의 `ddl-auto`라는 두 방식이 있다. Flyway는 셋째 방식이다. 9주차의 어느 단계도 만든 표를 고치지 않고 표를 더할 뿐이다. 보존해야 할 데이터가 든 표를 처음 `ALTER`해야 할 때 들인다. 그때는 버전 붙은 마이그레이션 없이 바꿀 수 없다.
- **스트리머의 엔티티 `PurchaseActivity`에 의존한다.** commerce-batch가 commerce-streamer의 모듈을 들이면 두 맥락이 표 하나가 아니라 클래스까지 나눈다. 스트리머가 엔티티를 고치면 배치가 다시 빌드되어야 하고, 엔티티가 되는 순간 위의 `ddl-auto` 문제가 스트리머의 표에 생긴다. 계약은 네 열과 `deleted_at is null`, `confirmed_at`의 인덱스다([`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)). 그 이상을 알 까닭이 없다.

## 대가

- `local`, `test` 밖에서는 랭킹의 표를 사람이 적용한다. 빠뜨리면 job이 표가 없다는 SQL 오류로 실패한다. Batch 메타데이터도 이미 그렇다.
- 스키마가 SQL 파일에 있고 코드의 SQL은 문자열이다. 컴파일이 열 이름의 어긋남을 잡지 못하고, 실제 MySQL에서 job을 돌리는 테스트가 잡는다.
- commerce-batch의 테스트는 `purchase_activity`를 테스트 전용 SQL 파일(`sql/purchase-activity.sql`)로 만든다. 계약의 열과 `id`, `deleted_at`, `confirmed_at`의 인덱스다. 스트리머의 진짜 표와 어긋나도 이 앱의 테스트는 알지 못한다. 넷째 단계(`feat/weekly-ranking-late-purchase`)의 연결 테스트가 두 앱을 함께 돌려 잡는다. (2026-11-19 넷째 단계: `WeeklyRankingFlowTest`가 스트리머가 만든 진짜 `purchase_activity`에서 배치를 자식 프로세스로 돌린다. 어긋남은 드러나지 않았고, 테스트 전용 파일은 그대로 남아 이 앱의 테스트가 쓴다. [주간 랭킹 설계](../design/weekly-ranking.md) 14.4절.)
- commerce-batch와 commerce-api가 다른 구조를 가진다. 같은 저장소에서 영속성의 자리가 앱마다 다르다.
- 이 규칙을 막는 ArchUnit 규칙은 없다. 리뷰가 지킨다.

## 다시 볼 조건

- 보존해야 할 데이터가 든 랭킹의 표를 처음 `ALTER`해야 하면 Flyway를 들인다.
- commerce-batch가 DB 말고 다른 것(메시지, HTTP)에 쓰거나, 같은 SQL을 여러 job이 부르게 되면 포트를 다시 본다.
- commerce-api가 랭킹의 표를 읽는 길(셋째 단계 `feat/weekly-ranking-publish`)이 엔티티를 원하면, 그 앱의 `ddl-auto`가 랭킹의 표에 닿지 않게 하는 방법을 그때 정한다. (2026-11-19 셋째 단계: 엔티티를 두지 않았다. commerce-api는 세 표를 계약의 컬럼만 `JdbcClient`로 읽는다. [ADR 0039](./0039-what-a-ranking-period-shows-is-decided-by-a-publication-row-that-only-a-later-revision-replaces.md).)
