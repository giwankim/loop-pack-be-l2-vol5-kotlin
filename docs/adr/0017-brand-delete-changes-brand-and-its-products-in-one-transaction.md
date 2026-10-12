---
status: accepted
date: 2026-10-09
---

# 브랜드 삭제는 브랜드와 그 상품을 하나의 트랜잭션에서 바꾼다

3주차 과제는 `DELETE /api-admin/v1/brands/{brandId}`의 계약을 바꾼다. 삭제되지 않은 상품이 남으면 409로 거절하던 동작이, 브랜드와 그 브랜드의 삭제되지 않은 상품을 함께 삭제하는 동작이 된다. 과제는 브랜드와 연결 상품의 상태 변경을 같은 성공·실패로 묶으라고 한다. 일부 성공 응답과 상품마다의 별도 commit을 금지하고, AI에게 찾게 할 반례에 "독립 commit"을 넣었다. 중간 실패 테스트는 실제 변경 SQL이 나간 뒤 다음 저장 단계에서 예외를 내고, 트랜잭션이 끝난 뒤 변경 전 상태를 확인해야 한다.

[카탈로그 설계 5.1](../design/catalog.md)은 브랜드와 상품을 각자 애그리거트로 두고(선택 B), 카탈로그 변경의 각 트랜잭션은 애그리거트 하나만 바꾼다고 정했다. 예외는 주문 확정([ADR 0003](./0003-confirm-order-in-one-transaction.md)) 하나였다. 5.1의 다시 볼 조건 "브랜드 단위로 상품을 한꺼번에 바꾸는 요구가 생기면 A를 다시 본다"가 이번에 걸렸다.

1. **애그리거트는 둘로 둔다.** 브랜드 삭제를 ADR 0003에 이은 두 번째 명시적 예외로 둔다. 한 트랜잭션이 `Brand` 하나와 그 브랜드의 삭제되지 않은 `Product` 전부를 바꾼다. 재고가 0인 상품도 포함한다. 이미 삭제된 상품의 `deletedAt`은 그대로다. 연결 상품이 없는 브랜드도 같은 흐름으로 삭제된다. 다른 브랜드와 상품, 저장된 주문, 좋아요 행은 바꾸지 않는다([ADR 0001](./0001-soft-delete-catalog-hard-delete-like.md)).
2. **brand가 포트를 선언하고 product가 구현한다.** brand 조각이 `application/brand/required`에 `ProductDeleter`(`deleteAllOfBrand(brandId: Long)`)를 둔다. `ProductModifyService`가 `ProductRegister`, `StockDeductor`와 나란히 이것을 직접 구현한다. `BrandModifyService.delete`가 같은 트랜잭션 안에서 부른다. 타입 의존은 product → brand 한 방향이고, `HexagonalArchitectureTest.applicationSlicesAreFreeOfCycles`가 그것을 지킨다. 실행 시점의 빈은 brand → product → brand로 이어지지만 순환으로 보지 않는다. splearn의 아키텍처 테스트와 비순환 의존 원칙이 보는 것은 타입의 의존이다.
3. **순서는 브랜드 잠금, 상품 삭제, 브랜드 삭제 표시다.** 브랜드를 잠가 읽는다. 없거나 삭제됐으면 `BRAND_NOT_FOUND`다. 그다음 `ProductDeleter`로 상품을 삭제하고, 브랜드에 삭제 시각을 찍어 저장한다. 무엇을 어떻게 잠그는지는 [ADR 0018](./0018-pessimistic-row-locks-in-one-global-order.md)에 있다.
4. **`ActiveProductChecker`는 사라진다.** 삭제를 막던 조건이 없어지므로 `BrandValidator.validateForDelete`, `ProductFinder.hasActiveProducts`, `ProductRepository.existsByBrandId`, `ErrorType.BRAND_HAS_PRODUCTS`(409)도 지운다.

## 고르지 않은 것

- **Spring 이벤트와 `@TransactionalEventListener(AFTER_COMMIT)`, `@Async`로 느슨하게 잇는다.** 상품 삭제가 브랜드의 커밋 뒤에 다른 스레드의 새 트랜잭션에서 돈다. 과제가 금지한 독립 commit이다. 상품 쪽이 실패해도 브랜드는 삭제된 채 남는다. 그 사이 삭제된 브랜드의 상품이 목록, 새 주문, 확정에 남는다. Spring 6.1부터는 이런 리스너에 `REQUIRES_NEW`, `NOT_SUPPORTED`가 아닌 `@Transactional`을 허용하지 않는다. 프레임워크도 그 리스너가 발행자의 트랜잭션 밖에 있다고 말하는 셈이다.
- **트랜잭션 안에서 도는 이벤트를 쓴다.** `@EventListener`나 `@TransactionalEventListener(BEFORE_COMMIT)`가 그렇다. 과제는 지킨다. 그러나 두 애그리거트를 한 트랜잭션에서 바꾸는 것은 같고, 예외를 이름 없이 숨길 뿐이다. `BEFORE_COMMIT`은 커밋 도중에 돌아서 상품 삭제의 시점과 잠금 순서가 메서드 본문에 보이지 않는다. 이 코드베이스의 첫 이벤트가 된다.
- **하나의 애그리거트로 합친다(5.1의 A).** 5.1이 A를 버린 까닭이 그대로다. 상품 목록은 브랜드를 가로지르고, 상품 하나를 고치려고 브랜드의 상품 전체를 싣게 된다. 주문 확정이 브랜드 애그리거트 전체를 잠그게 된다.
- **두 조각 위에 조율 조각을 둔다.** 예를 들어 `application.catalog`의 Service가 브랜드와 상품을 부른다. 실행 시점의 빈 그래프도 한 방향이 된다. 그 대신 용어집에 없는 조각이 생긴다. `BrandRegister.delete`는 혼자 부르면 안 되는 "브랜드에만 표시"로 쪼개진다.
- **product의 provided 포트가 `ProductDeleter`를 상속한다(`ProductRegister : ProductDeleter`).** 그러면 관리자 웹 어댑터가 브랜드만 부를 명령을 갖게 된다. 쓰기 포트는 부르는 쪽에 따라 나눈다([ADR 0013](./0013-commerce-api-follows-splearn-hexagonal-structure.md)). 같은 까닭으로 이제는 어느 조각의 provided 포트도 다른 조각의 required 포트를 상속하지 않는다. Service가 맡는 역할 인터페이스를 모두 직접 구현한다. `LikeFinder : LikeCounter`도 `LikeQueryService : LikeFinder, LikeCounter`로 바뀐다. ADR 0013에 날짜 메모를 단다.
- **이름을 `ProductRemover`로 한다.** 용어집의 "삭제하다"는 remove를 피할 말로 둔다. JPA의 `EntityManager.remove`와 Spring Data의 `removeBy…`·`deleteBy…`는 행을 지운다. 이 포트는 삭제 시각만 찍는다.

## 대가

- 브랜드 삭제가 그 브랜드의 상품 수만큼 행을 잠그고, 엔티티로 읽고, UPDATE를 보낸다. 문장 수는 ADR 0018의 배치 설정이 줄인다.
- `deleteAllOfBrand`를 부른 뒤에는 삭제된 상품이 영속성 컨텍스트에 남는다. `@SQLRestriction`은 SQL에만 붙으므로, 같은 트랜잭션에서 `EntityManager.find`로 그 상품을 다시 찾으면 삭제된 인스턴스가 돌아온다. 지금은 연쇄 뒤에 상품을 읽는 곳이 없고, KDoc이 이것을 적는다.
- 5.1의 첫 규칙 "카탈로그 변경의 각 트랜잭션은 애그리거트 하나만 바꾼다"의 예외가 둘이 된다.
- 브랜드가 상품이라는 개념을 안다. 포트가 brand에 있기 때문이다. 그것이 용어집이 브랜드에 둔 규칙("삭제하면 그 브랜드의 삭제되지 않은 상품도 함께 삭제된다")이므로 받아들인다.

## 다시 볼 조건

- 한 브랜드의 상품이 배치로도 감당하지 못할 만큼 많아지면 한 트랜잭션의 잠금 수와 문장 수를 다시 본다. 그때는 과제의 원자성 요구와 다시 견준다.
- 브랜드 삭제가 다른 조각(좋아요 등)의 변경까지 끌어들이면 조율 조각을 다시 본다.

## 바꾸는 결정

카탈로그 설계 §4의 API 표와 오류 코드, 5.1, 5.8, 5.16, §6, §7, 도메인 문서 `docs/domain/catalog.md`, ADR 0013의 역전 목록은 구현하는 작업에서 원문을 두고 날짜 메모를 단다. 지금의 모양을 적는 도메인 문서는 그 자리에서 고친다. `CONTEXT.md`의 브랜드, 삭제됨, 삭제되지 않은 항목은 이 결정과 함께 고쳤다.
