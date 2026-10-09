---
status: accepted
date: 2026-10-09
---

# 브랜드·상품 행의 변경은 비관적 잠금으로 지키고, 잠금 순서는 하나로 둔다

상품을 바꾸는 모든 곳은 잠그지 않고 읽은 엔티티를 dirty checking으로 저장한다. 관리자의 수정, 재고 수정, 삭제와 주문 확정의 차감이 그렇다. `@DynamicUpdate`가 없어서 Hibernate는 행 전체를 쓴다. 격리 수준은 MySQL 기본값인 REPEATABLE READ이고, 일반 `SELECT`는 트랜잭션의 첫 읽기 때의 스냅샷을 본다. 그래서 다른 트랜잭션이 그 사이 커밋한 `deleted_at`이나 `stock`을 옛 값으로 덮는다. 브랜드 삭제가 커밋한 뒤 확정이 상품을 쓰면 `deleted_at = NULL`로 상품이 되살아난다. 순서가 반대면 확정의 차감이 사라진다. 같은 재고를 두고 두 확정이 겹쳐도 같은 꼴이다([포인트·주문 설계 18.2](../design/points-orders.md)). 상품 등록은 삭제되지 않은 브랜드를 읽고 상품을 넣는데, 그 사이 브랜드 삭제가 커밋하면 삭제된 브랜드 아래 삭제되지 않은 상품이 남는다. [카탈로그 설계 §7](../design/catalog.md)이 이 경합을 적어 두고 "주문이 상품을 읽기 시작하면 다시 본다"고 했는데, 그 조건은 이미 걸렸다. [ADR 0003](./0003-confirm-order-in-one-transaction.md)이 후속 과제로 미룬 잠금이다.

과제는 비관적 잠금, 낙관적 잠금, 조건부 UPDATE 가운데 하나를 골라 실제 SQL에 적용하라고 한다. 재고 5개에 주문 8개가 동시에 확정되면 5개가 성공하고 3개가 실패하고 재고가 0이어야 한다. 이번 결정은 브랜드와 상품 행의 몫이다. 포인트 계정의 잠금은 따로 정한다.

1. **바꾸는 트랜잭션은 그 행을 처음 읽을 때 잠근다.** 잠금 읽기(`FOR UPDATE`, `FOR SHARE`)로 읽고, 도메인 메서드로 바꾸고, dirty checking으로 저장한다. 잠금은 커밋이나 롤백까지 간다.
   - 상품 쓰기: 관리자의 수정, 재고 수정, 삭제와 `deduct`는 `ProductFinder.findForUpdate(id)`로 읽는다.
   - 주문 확정의 첫 상품 읽기는 `ProductFinder.findForUpdateOrNull(id)`다. 상품 행만 잠그고 브랜드를 조인하지 않는다.
   - 브랜드 삭제의 연쇄([ADR 0017](./0017-brand-delete-changes-brand-and-its-products-in-one-transaction.md))는 `findForUpdateByBrandIdOrderById(brandId)`로 그 브랜드의 삭제되지 않은 상품을 잠가 읽고, 각각 `delete()`를 부른다.
   - 브랜드의 삭제와 수정은 `BrandFinder.findForUpdate(id)`, 상품 등록은 `BrandFinder.findForShare(id)`로 브랜드를 읽는다. 같은 브랜드에 상품을 등록하는 요청끼리는 서로 막지 않고, 브랜드 삭제만 그것들과 엇갈린다.
   - 주문 생성(DRAFT)과 좋아요는 상품을 바꾸지 않으므로 잠그지 않는다. 브랜드 삭제와 겹치면 "생성 뒤 삭제"로 끝나고, 확정이 다시 확인한다.
2. **첫 읽기가 잠가야 한다.** 한 영속성 컨텍스트에서 Hibernate는 이미 읽은 엔티티를 다시 돌려줄 때 처음 읽은 필드 값을 그대로 둔다. 나중의 잠금 읽기가 최신 행을 가져와도 메모리의 낡은 값이 이긴다. 확정에서는 `availableProduct`가 잠그고, `deduct`의 잠금 읽기는 이미 가진 잠금을 다시 확인할 뿐이다.
3. **잠금 순서는 브랜드 → 상품(id 오름차순) → 포인트 계정이다.** 여러 상품을 잠그는 곳은 모두 id 오름차순이다.
   - 확정의 품목은 생성 때 `productId` 순으로 정렬되어 있다(`@OrderBy("productId ASC")`).
   - 연쇄는 `brand_id` 인덱스를 따라 잠근다. 그 인덱스의 항목이 `(brand_id, id)`이므로 id 순이다. 잠금은 훑는 인덱스의 순서로 걸리고 `ORDER BY`가 정하지 않는다. `OrderById`는 그 뜻을 적고 결과의 순서를 고정한다.
   - 확정은 브랜드를 잠그지 않는다.
   - `hibernate.order_updates: true`가 flush 때의 UPDATE도 엔티티 종류와 id 순으로 보낸다.
4. **삭제 여부는 잠금을 얻은 뒤에 본다.** 잠금 읽기는 기다린 뒤 가장 최근에 커밋된 행을 읽는다. 그래서 `@SQLRestriction("deleted_at is null")`이 그 사이 커밋된 삭제를 걸러 낸다. 이 동작은 첫 테스트가 MySQL에서 확인한다. 브랜드 삭제가 상품을 함께 삭제하고 상품 등록이 브랜드를 잠그므로, 커밋된 모든 상태에서 "삭제된 브랜드의 상품은 삭제됐다"가 성립한다. 확정이 상품 행만 보는 까닭이다. 읽기 쪽의 브랜드 필터(목록의 inner join, 주문 생성의 `findByIdWithActiveBrand`, 좋아요한 상품 목록의 그래프)는 방어로 남긴다.
5. **저장소 포트는 Spring Data의 파생 이름에 `@Lock`을 단다.** `findForUpdateById`(`PESSIMISTIC_WRITE`), `findForShareById`(`PESSIMISTIC_READ`), `findForUpdateByBrandIdOrderById`(`PESSIMISTIC_WRITE`)가 그렇다. Spring Data는 `find`와 `By` 사이를 설명으로 보므로 `@Query`가 필요 없다. 파생 조회는 상품 표만 읽는다. `brand`가 LAZY라서 잠금이 상품 행에만 걸린다. Finder의 잠금 메서드는 트랜잭션 속성을 따로 달지 않는다. 대신 KDoc에 "호출자의 쓰기 트랜잭션 안에서만 부른다. 잠금은 그 트랜잭션이 끝날 때 풀린다."를 적는다. 부르는 곳은 모두 쓰기 트랜잭션에 참여하므로 Query Service의 `readOnly`는 걸리지 않는다.
6. **`product.brand_id` 인덱스를 엔티티에 적고 테스트로 고정한다.** 지금은 InnoDB가 외래 키를 위해 만든 암묵 인덱스뿐이다. 그 인덱스가 없으면 REPEATABLE READ의 잠금 읽기가 훑은 모든 행을 잠가, 상품 표 전체가 브랜드 삭제의 커밋까지 잠긴다.
7. **잠금 대기는 3초다.** `modules/jpa`의 `jpa.yml`에서 `data-source-properties`에 `sessionVariables: innodb_lock_wait_timeout=3`을 둔다. 모든 프로필과 앱에 걸린다. 쿼리 힌트(`jakarta.persistence.lock.timeout`)는 잠금 조회에만 닿고 flush 때의 UPDATE에는 닿지 않으므로 세션 값으로 둔다. Hikari의 `connection-timeout` 3초와 같은 값이다.
8. **잠금 실패는 409 `CONCURRENT_REQUEST`다.** 대기 시간 초과(MySQL 1205)와 교착(1213)은 Spring의 `PessimisticLockingFailureException`과 그 하위 타입으로 온다. `ApiControllerAdvice`는 이것을 409로 바꾸고 code `CONCURRENT_REQUEST`와 "다른 요청과 겹쳐 처리하지 못했습니다. 다시 시도해 주세요."를 돌려준다. 클라이언트가 이 409를 다른 409와 구별해야 하므로 code를 새로 갖는다. 이 409는 요청의 어떤 변경도 반영되지 않았다는 뜻이다. 대기 시간 초과면 Spring이 트랜잭션을 되돌린다. 교착이면 InnoDB가 이미 되돌렸다. 잠금 대기는 커밋 전에만 일어난다. 그래서 다시 보내도 중복이 생기지 않는다. [ADR 0005](./0005-no-request-idempotency-until-a-retrying-caller.md)가 다시 열 조건으로 둔 것은 결과를 모르는 재시도이므로 여기에 걸리지 않는다.
9. **UPDATE는 묶어 보낸다.** `hibernate.jdbc.batch_size: 50`과 `hibernate.order_updates: true`를 `jpa.yml`에 둔다. 연쇄의 UPDATE N개가 ⌈N/50⌉번의 왕복으로 나간다. `rewriteBatchedStatements=true`는 이미 있다. 식별자가 `IDENTITY`라서 INSERT는 묶이지 않는다.

## 고르지 않은 것

- **낙관적 잠금(`@Version`).** 재고 5개에 주문 8개면 재고가 남아 있어도 버전 충돌로 실패하는 주문이 생긴다. 과제의 5/3을 맞추려면 재시도가 필요하다. 브랜드 삭제는 상품 하나만 동시에 바뀌어도 통째로 실패한다.
- **조건부 UPDATE(`stock = stock - ? where … and stock >= ?`).** 싸고 정확하다. 그러나 재고 규칙이 `Product`에서 SQL로 옮겨 가고, 영속성 컨텍스트에 읽어 둔 엔티티가 낡는다.
- **`@DynamicUpdate`만 단다.** 바뀐 열만 쓰므로 삭제와 확정이 겹치면 "확정 뒤 삭제"처럼 끝난다. 그러나 같은 재고를 두고 겹친 두 확정은 그대로 차감 하나를 잃는다.
- **연쇄를 벌크 UPDATE 하나로 보낸다.** InnoDB의 UPDATE도 잠금 읽기라서 같은 행을 같은 순서로 잠근다. 그러나 `BaseEntity.delete()`와 `@PreUpdate`를 건너뛰므로 "삭제"의 정의가 둘이 된다. 같은 트랜잭션에서 이미 읽힌 상품은 메모리에서 살아 있는 채로 남고, 나중의 flush가 `deleted_at = NULL`을 다시 쓸 수 있다. 다른 쓰기와 달리 `@Modifying @Query`가 필요하다.
- **Finder의 잠금 메서드에 `@Transactional(propagation = MANDATORY)`를 단다.** 트랜잭션 밖에서 부르면 잠금이 곧바로 풀리는 실수를 예외로 바꾼다. 그러나 지금 부르는 곳은 모두 쓰기 트랜잭션 안에 있다. 그리고 클래스의 `readOnly = true`와 겹쳐 읽힌다.
- **확정이 지금의 `join fetch`에 잠금을 건다.** MySQL의 `FOR UPDATE`는 조인한 브랜드 행도 잠근다. 같은 브랜드의 확정이 서로 기다리게 되고, 브랜드 → 상품으로 잠그는 브랜드 삭제와 순서가 엇갈려 교착할 수 있다.
- **상품 행만 잠그고 브랜드 행은 잠그지 않는다.** 아직 없는 행, 곧 새 상품은 잠글 수 없다. InnoDB의 외래 키 검사는 부모 행에 공유 잠금을 건다. 그러나 논리 삭제는 행을 남기므로 브랜드 삭제가 커밋된 뒤에도 그 검사가 통과한다.
- **잠금 실패를 500으로 두고, 대기를 기본 50초로 둔다.** 다시 시도하라는 답이 50초 뒤에 오고, 그동안 커넥션 하나가 묶인다.

## 대가

- 같은 상품을 바꾸는 요청끼리 서로 기다린다. 관리자의 상품 수정과 확정도 그렇다. 재고 행만 따로 잠그려면 재고를 별도 엔티티로 빼야 한다([카탈로그 설계 5.3](../design/catalog.md)의 다시 볼 조건). 지금은 하지 않는다.
- 잠금 메서드는 호출자의 트랜잭션 안에서만 뜻이 있는데, 그것을 막는 장치는 KDoc뿐이다.
- 확정은 품목마다 잠금 읽기를 두 번 보낸다(`availableProduct`와 `deduct`).
- `jpa.yml`의 대기 시간과 배치 설정은 commerce-batch와 commerce-streamer에도 걸린다.
- 잠금을 확인하는 테스트는 실제로 커밋해야 하므로 롤백으로 정리할 수 없다. 커밋하는 application 테스트의 기반 클래스가 하나 더 생긴다([ADR 0012](./0012-tests-inherit-setup-from-abstract-base-classes.md)에 날짜 메모). 경합 테스트는 한 트랜잭션이 잠금을 쥔 채 멈춰 있는 동안 다른 쪽이 기다리는지를 본다. 잠금을 빼고 한 번 돌려 실패하는 것을 확인한다.

## 이번 범위 밖에 남은 빈틈

- **같은 주문의 동시 확정.** 주문 행을 잠그지 않으므로 둘 다 DRAFT를 읽고 재고를 두 번 차감한다. 포인트 계정의 잠금(충전과 결제, 같은 사용자의 여러 확정)과 함께 따로 정한다.
- **브랜드 이름의 중복.** 동시에 들어온 등록이나 이름 변경은 서로 다른 행을 쓰므로 브랜드 행의 잠금으로 막지 못한다. 이름에는 유일 인덱스가 없다(ADR 0001).

## 다시 볼 조건

- 잠금 대기 초과나 409가 실제로 관찰되면 대기 시간과 잠금 범위를 다시 본다.
- 관리자의 상품 수정과 확정이 서로 막는 것이 문제가 되면 재고 엔티티(5.3의 B)나 조건부 UPDATE를 본다.
- 한 브랜드의 상품이 배치로도 감당하지 못할 만큼 많아지면 연쇄의 벌크 UPDATE를 다시 본다.

## 바꾸는 결정

ADR 0003의 "이번 검증 범위"가 미룬 잠금과 관리자 변경과의 경합 가운데 상품과 브랜드의 몫을 이 결정이 맡는다. ADR 0003, 카탈로그 설계 §7과 5.35, 포인트·주문 설계 18.2는 구현하는 작업에서 원문을 두고 날짜 메모를 단다.
