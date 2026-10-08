---
status: accepted
date: 2026-10-08
---

# 모든 엔티티는 `BaseEntity`를 상속하고, 애그리거트 루트는 기본으로 논리 삭제한다

`Order`와 `OrderLineItem`은 `BaseEntity`를 상속하지 않았다. 상속하면 카탈로그의 논리 삭제를 물려받는데, 주문을 지우는 유스케이스가 없다는 까닭이었다([포인트·주문 설계 13](../design/points-orders.md)). 그런데 `User`와 `PointAccount`도 지우는 유스케이스가 없는데 `BaseEntity`를 상속했고, 삭제 필터만 없었다. 같은 사실에서 반대의 선택이 나왔다. 행이 사라지는지 삭제 표시가 남는지가, 엔티티가 어쩌다 기반 클래스를 상속했는지에 달려 있었다.

시각도 두 벌이었다. `BaseEntity`는 JVM 기본 시간대 `Asia/Seoul`의 `ZonedDateTime.now()`를 자르지 않고 찍었다. `Order`는 `Instant.now()`를 마이크로초로 잘라, 첫 응답이 `datetime(6)`에서 다시 읽은 값과 같았다. 그래서 브랜드·상품 관리자 JSON은 `+09:00`으로, 주문 JSON은 `Z`로 나갔다. 저장은 어느 쪽이든 UTC다(`NORMALIZE_UTC`, `jdbc.time_zone: UTC`).

규칙 여섯을 둔다.

1. **모든 엔티티가 `BaseEntity`를 상속한다.** 애그리거트에 딸린 엔티티도 그렇다. 이름은 그대로 두고, KDoc이 이 규칙을 적는다.
2. **애그리거트 루트는 지우는 것이 기능이 아니어도 논리 삭제를 기본으로 한다.** 루트마다 `@SQLRestriction("deleted_at is null")`과 "삭제된 것은 없는 것" 저장소 테스트를 둔다. [ADR 0001](./0001-soft-delete-catalog-hard-delete-like.md)이 브랜드·상품에 요구한 그대로다. Hibernate는 `@MappedSuperclass`의 `@SQLRestriction`을 무시하므로 엔티티마다 붙인다. 지금의 루트는 `Brand`, `Product`, `User`, `PointAccount`, `Order`다.
3. **딸린 엔티티는 루트를 따른다.** 필터를 붙이지 않고 `delete()`를 부르지 않으며, 루트를 거쳐서만 읽는다. 딸린 엔티티를 가리는 것은 루트의 필터다. `OrderLineItem`에 필터를 두지 않는 까닭이 있다. 주문은 품목을 `@EntityGraph` 조인으로 읽고([ADR 0014](./0014-finders-load-whole-aggregates.md)), 대상의 `@SQLRestriction`은 조인에도 붙는다. `ProductRepository`가 브랜드에서 이미 기대는 동작이다. 필터에 걸린 품목은 주문에서 빠지지만 `totalAmount`는 그 품목을 계속 센다.
4. **`Like`는 하나뿐인 예외로 행을 지운다(ADR 0001).** 취소한 좋아요가 행으로 남으면 같은 쌍을 다시 누를 때 `(user_id, product_id)` 유일 키와 충돌한다. `delete()`는 지금처럼 쓰지 않는다.
5. **`BaseEntity`의 시각은 마이크로초로 자른 `Instant`다.** `createdAt`, `updatedAt`, `deletedAt` 셋 다 그렇다. `Order`는 자기 `id`와 `createdAt`을 버리고 물려받는다. `confirmedAt`은 `Order`가 스스로 자른다.
6. **`Like`는 `userId`와 `productId`를 `@NaturalId`로 표시한다.** 좋아요의 정체가 그 쌍이라는 것을 적는다. Spring Data의 파생 조회(`findByUserIdAndProductId`, `existsByUserIdAndProductId`)는 이 애노테이션을 보지 않고 그대로 SQL을 보낸다. 자연 식별자로 읽으려면 포트 뒤에서 `Session` API를 써야 하고, 2차 캐시도 없다. 그래서 지금은 정체를 적고 불변을 지킬 뿐 읽는 방식은 바꾸지 않는다. 이름 있는 `UK_LIKES_USER_ID_PRODUCT_ID`는 그대로다. Hibernate는 자연 식별자 열에 제 유일 키를 만들 수 있으므로, 쌍의 유일 인덱스가 이름 있는 그것 하나뿐임을 `LikeRepositoryTest`가 `information_schema`로 고정한다.

## 고르지 않은 것

- **`BaseEntity`를 애그리거트 루트에만 둔다.** 이 규칙을 쓰려면 애그리거트의 경계를 알아야 한다. ADR 0014가 그 경계를 막 다시 그었다. "모든 엔티티"는 판단 없이 쓸 수 있다.
- **딸린 엔티티에도 필터를 둔다.** 루트가 품목을 조인으로 읽을 때 필터가 붙는다. 필터에 걸린 품목이 빠지면 주문의 불변식(총액은 품목 금액의 합)이 깨진다.
- **`Order`를 `BaseEntity` 밖에 둔다.** 지금까지의 모양이다. "지우는 유스케이스가 없다"는 같은 사실이 `User`·`PointAccount`와 반대의 선택을 낳는다.
- **`ZonedDateTime` 기반에 마이크로초 자르기만 더한다.** 정밀도는 맞는다. 그러나 JSON의 오프셋이 JVM 기본 시간대를 따르므로 주문과 카탈로그가 계속 다르게 나간다. 저장이 UTC인데 메모리에서만 시간대를 들고 있을 까닭이 없다.
- **`Like`도 논리 삭제한다.** 취소한 행이 다시 누르기와 유일 키에서 충돌한다. 유일 키를 `deleted_at`까지 넓혀도 안 된다. MySQL은 유일 인덱스에서 `NULL`끼리 같다고 보지 않으므로 살아 있는 쌍의 중복을 막지 못한다.
- **`@NaturalId` 대신 `Like`에 복합 기본 키를 둔다.** `BaseEntity`의 대리 키 `id`와 맞지 않아 규칙 1을 깨야 한다.

## 대가

- `OrderLineItem`과 `Like`가 아무도 부르지 않는 공개 `delete()`를 가진다. `BaseEntity.delete()`는 공개 `final`이고, 부르는 것을 막는 장치는 없다. ArchUnit 규칙을 두지 않는다는 앞선 결정과 같다.
- 새 루트에서 `@SQLRestriction`을 빠뜨려도 컴파일이 잡지 못한다(ADR 0001). 엔티티마다의 저장소 테스트가 그것을 확인한다.
- `OrderLineItem`은 바뀌지 않는 열 셋(`created_at`, `updated_at`, `deleted_at`)을 가진다.
- 브랜드·상품 관리자 응답의 `createdAt`·`updatedAt`은 JSON 오프셋 표기가 `+09:00`에서 `Z`로 바뀐다. 가리키는 순간은 같다.
- 행을 SQL로 직접 넣는 테스트는 새 `not null` 열(`orders.updated_at`, `order_line_item`의 두 시각)을 채워야 한다. `OrderApiTest`의 제약 테스트가 그렇게 바뀌었다. 빠뜨리면 MySQL의 기본값 없음 오류도 `DataIntegrityViolationException`이라서, 확인하려던 제약에 닿기 전에 테스트가 통과한다.

## 다시 볼 조건

- 사용자나 주문을 지우는 일이 기능이 되면 딸린 것들(포인트 계정, 좋아요, 주문)을 어떻게 할지 그때 정한다. 지금의 필터는 행을 가릴 뿐이고, 외래 키와 유일 키는 삭제된 행도 센다. 예를 들어 삭제된 사용자의 계정이 `UK_POINT_ACCOUNT_USER_ID`를 계속 차지한다.
- 딸린 엔티티를 루트 밖에서 읽어야 하는 일이 생기면 규칙 3을 다시 본다.

## 바꾸는 결정

ADR 0001에서 브랜드·상품만 하던 논리 삭제는 이 결정의 규칙 2로 넓어졌고, 좋아요의 물리 삭제는 이 결정의 예외로 남는다. ADR 0001, 포인트·주문 설계 8.1·12.1·13, 카탈로그 설계 5.27·7은 원문을 그대로 두고 날짜 메모를 달았다. 카탈로그 설계의 클래스 다이어그램은 `deletedAt`의 타입만 `Instant?`로 그 자리에서 고쳤다. 지금의 모양을 적는 도메인 문서(`docs/domain`)도 그 자리에서 고쳤다.

`CONTEXT.md`는 그대로다. 그 "삭제됨"은 도메인이 할 수 있는 일을 적는데, 지울 수 있는 것은 브랜드와 상품뿐이다. 사용자·주문의 논리 삭제는 기술의 기본값이지 도메인의 말이 아니다.
