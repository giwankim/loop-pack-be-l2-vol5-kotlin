---
status: accepted
date: 2026-10-08
---

# commerce-api는 splearn의 헥사고날 구조를 따른다

commerce-api는 `interfaces` → `application` → `domain` ← `infrastructure`로 나뉜 계층형이었다. 저장 약속은 `domain`에, 그 구현(`<X>JpaRepository`와 `<X>RepositoryImpl`)은 `infrastructure`에 있었다. 조각 사이 호출은 Service가 다른 조각의 `domain` 저장소를 바로 부르는 모양이었다. application에서 나가는 조각 사이 의존 11개 중 10개가 그랬고, 주문 확정은 `OrderService`가 주문·상품·포인트 계정 세 애그리거트를 직접 바꿨다. Request의 이름과 자리([카탈로그 설계 5.17](../design/catalog.md))와 검증 배치(5.18)는 이미 [splearn-1-part2](https://github.com/tobyspringboot/splearn-1-part2/tree/2d1acdffdd0ad2aa4d400d0ed84544fd07ca0401)(`2d1acdf`, 이하 splearn)를 따랐다. 구조도 splearn을 따르기로 하고, `refactor/hexagonal` 브랜치에서 옮겨 본 결과를 보고 채택했다. URL, 상태 코드, 응답 본문, 오류 형식은 바뀌지 않았다. 바뀐 것은 생성된 API 문서의 스키마 이름뿐이다(대가).

- 패키지는 `adapter.webapi`, `adapter.persistence`, `application.<조각>`, `domain`이다. `adapter.webapi`에는 컨트롤러(`<X>Api`), ApiSpec, 응답, 웹 공용 타입이 있고 API 버전은 `adapter.webapi.v1`에 남는다. `ApiControllerAdvice`는 `adapter` 바로 아래에 있다. `adapter.persistence`에는 QueryDSL 목록 어댑터가 있다.
- `application.<조각>.provided`에 포트 인터페이스, Request, `Info`를 둔다. 포트는 14개다. 읽기는 조각마다 Finder 하나다(`BrandFinder`, `ProductFinder`, `LikeFinder`, `OrderFinder`, `PointAccountFinder`, `UserFinder`). 쓰기는 부르는 쪽(관리자, 고객, 다른 조각)에 따라 나누고, 한 쪽이 부르는 유스케이스가 둘이면 포트도 둘로 둔다. 관리자의 `BrandRegister`·`ProductRegister`, 고객의 `Liker`·`OrderCreator`·`OrderConfirmer`·`PointCharger`, 주문 확정이 부르는 `StockDeductor`·`PointDeductor`다. 컨트롤러와 다른 조각은 포트만 받는다. (2026-10-08, [ADR 0014](./0014-finders-load-whole-aggregates.md): 사전 조건을 보는 `BrandValidator`와 `LikeValidator`가 더해져 포트는 16개다. 읽기는 여전히 조각마다 Finder 하나다. `Info`는 여러 애그리거트를 합친 `ProductInfo` 하나만 남았다.)
- `application.<조각>.required`에 저장소와, 다른 조각에 묻는 포트를 둔다.
  - 저장소 여섯은 Spring Data의 표지 `Repository<X, Long>`을 상속한 인터페이스이고 Spring Data가 구현을 만든다. `@Query`, `@EntityGraph`, 프로젝션도 여기에 선언한다.
  - QueryDSL 목록 둘은 순수 Kotlin 포트(`ProductListRepository`, `OrderListRepository`)이고, `adapter.persistence`의 `QuerydslProductListRepository`, `QuerydslOrderListRepository`가 구현한다.
  - Spring Data의 custom fragment는 쓰지 않는다. `<저장소 인터페이스 이름>Impl`이라는 이름의 클래스도 두지 않는다. Spring Data가 그런 이름의 빈을 패키지와 상관없이 그 저장소의 구현으로 붙이기 때문이다. 공용 `JpaConfig`는 저장소를 `com.loopers`에서 찾는다.
- 조각마다 Query Service(`<X>QueryService`, Finder를 구현)와 Modify Service(`<X>ModifyService`, 쓰기 포트를 구현)를 하나씩 둔다. user에는 `UserQueryService`만 있다. Service는 자기 애그리거트만 바꾸고, 다른 조각은 그 조각의 provided 포트로 부른다. splearn의 stereotype `@ApplicationService`, `@ValidatedApplicationService`, `@WebApiAdapter`를 `support.stereotype`에 두고, 읽기는 `readOnly = true`로 연다. (2026-10-08, [ADR 0014](./0014-finders-load-whole-aggregates.md): 사전 조건을 보는 조각(brand, like)에는 Validator를 구현한 `<X>ValidationService`가 하나 더 있다.)
- 저장소가 `application`으로 옮겨 오면서 brand ↔ product, product ↔ like 두 순환이 생겼다. 묻는 쪽이 `required`에 포트를 선언하고 답하는 쪽의 Finder가 그것을 상속해 끊는다. (2026-10-09, [ADR 0017](./0017-brand-delete-changes-brand-and-its-products-in-one-transaction.md): 이제 provided 포트는 다른 조각의 required 포트를 상속하지 않는다. Service가 맡는 역할 인터페이스를 모두 직접 구현한다. 쓰기 포트가 이미 그 모양이다(`ProductModifyService : ProductRegister, StockDeductor`). 그래서 아래 두 번째 역전이 바뀌었다. `LikeFinder`는 `LikeCounter`를 상속하지 않고, `LikeQueryService : LikeFinder, LikeCounter`가 둘을 각자 구현한다. 첫 번째 역전 `ProductFinder : ActiveProductChecker`는 브랜드 삭제 연쇄가 `ActiveProductChecker`를 지울 때 함께 사라진다. 의존 방향은 그대로이고, `@Lazy`가 끊는 빈 순환도 그대로다.)
  - brand의 `ActiveProductChecker`("삭제되지 않은 상품이 남았는가")를 `ProductFinder`가 구현한다. (2026-10-09, [ADR 0017](./0017-brand-delete-changes-brand-and-its-products-in-one-transaction.md): 브랜드 삭제 연쇄가 `ActiveProductChecker`를 지웠다. 지금은 brand의 `ProductDeleter`("이 브랜드의 삭제되지 않은 상품을 삭제하라")를 `ProductModifyService`가 직접 구현한다.)
  - product의 `LikeCounter`(좋아요 수)를 `LikeFinder`가 구현한다.
  - 의존은 도메인처럼 product → brand, like → product 한 방향이다.
- 주문 확정은 `OrderConfirmer`를 구현한 `OrderModifyService.confirm`이 맡는다. 자기 주문을 읽고, 품목마다 `StockDeductor`로 재고를, 주문 금액만큼 `PointDeductor`로 포인트를 차감한 뒤 주문을 확정한다. 두 포트는 트랜잭션을 새로 열지 않고 확정의 트랜잭션에 참여한다. 그래서 [ADR 0003](./0003-confirm-order-in-one-transaction.md)의 결정은 그대로다. 재고·잔액·주문의 변경은 함께 커밋되고 함께 되돌아간다.
- `@Valid`는 provided 포트 인터페이스의 Request 파라미터에 둔다(13곳). 구현 메서드에는 제약도 `@Valid`도 두지 않는다. Jakarta Validation 3.1 §5.6.5가 재정의 메서드에 파라미터 제약을 더하는 것을 금지하고, Hibernate Validator가 그런 Service를 HV000151로 거절한다. 검증하는 포트를 구현한 Service는 `@ValidatedApplicationService`를 단다. 컨트롤러의 `@Valid`는 그대로다. (2026-10-08: 지금은 17곳이다. `ProductFinder.findAllLikedBy`의 `ProductLikedListRequest`와, [ADR 0014](./0014-finders-load-whole-aggregates.md)의 `BrandValidator` 두 메서드가 더해졌고, [카탈로그 설계 5.36](../design/catalog.md)으로 `Liker.like`의 `LikeRequest`가 더해졌다.)
- `HexagonalArchitectureTest`(옛 `LayeredArchitectureTest`)가 다섯 계층(`domain`, `application`, `adapter.webapi`, `adapter.persistence`, `support`)의 의존 방향을 검사한다. 두 어댑터는 서로를 쓰지 못한다. 계층마다 조각 사이 순환을 막고, 도메인 조각은 서로 읽기만 한다.

## 근거

[#70](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/70)의 전환이 채택 기준 셋을 모두 채웠다. 증거는 #70의 [채택 결정](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/70#issuecomment-6051253493)이다.

1. 아키텍처 테스트를 포함한 전체 테스트가 통과했고, 새로 무시하거나 뺀 테스트가 없다. `0685847`에서 43개 클래스, 457개 테스트가 실패도 건너뜀도 없이 돌았다.
2. 세 곳이 읽기 쉽다. 한 조각의 Query·Modify Service(`ProductQueryService`, `ProductModifyService`)가 그렇다. 포트를 거치는 주문 확정(`OrderModifyService.confirm`)은 `CONTEXT.md`의 "주문을 확정하다"와 거의 같은 말로 읽힌다. 두 역전(`ProductFinder : ActiveProductChecker`, `LikeFinder : LikeCounter`)은 타입에서 읽힌다. (2026-10-09: 좋아요 수의 역전은 이제 Service의 구현 목록 `LikeQueryService : LikeFinder, LikeCounter`에서 읽힌다. 위 순환 항목의 메모를 보라. 브랜드 쪽 역전도 브랜드 삭제 연쇄 뒤로는 `ProductModifyService : ProductRegister, StockDeductor, ProductDeleter`에서 읽힌다.)
3. 3주차 작업을 시작하기 전에 끝났다.

`refactor/architecture`를 `faff0de`에서 `0685847`로 fast-forward했다. `git diff -M faff0de 0685847`로 세면 commerce-api의 주 코드는 100개 파일에서 117개가 되었다. 49개를 옮기거나 이름을 바꾸고, 39개를 더하고, 22개를 지우고, 8개를 고쳤다. provided 포트 14개와 required 포트 10개가 생겼고, Service는 5개에서 11개가 되었다.

## 고르지 않은 것

- **계층형을 그대로 둔다.** 옮긴 브랜치는 참고로만 남는다. 3주차 기능이 계층형 위에 쌓이므로 나중에 옮기려면 그 기능까지 옮겨야 한다.
- **모든 저장소를 순수 포트로 두고 `adapter.persistence`에서 구현한다.** splearn의 지침(`개발가이드.md`)이 적은 모양이다. 그러나 splearn의 코드에는 `adapter/persistence`가 없고, 모든 저장소가 `required`에서 Spring Data `Repository`를 상속한다. 이름만으로 끝나는 저장소마다 위임만 하는 구현 클래스가 생긴다. 저장소는 splearn의 코드를 따르고, Spring Data가 만들지 못하는 QueryDSL 목록만 지침을 따른다.
- **QueryDSL 목록을 Spring Data의 custom fragment로 붙인다.** fragment 구현은 fragment 인터페이스의 패키지 아래에서만 찾힌다(Spring Data Commons 4.1.1). `adapter.persistence`에 두면 찾지 못해 시작이 실패하고, 찾히는 자리에 두면 QueryDSL 코드가 `application`으로 들어온다.
- **엔티티 매핑을 `orm.xml`로 옮긴다.** `domain`에서 JPA 애노테이션을 뺄 수 있지만 이번 범위가 아니다. 엔티티의 JPA 애노테이션은 그대로다.

## 대가

- `LikeQueryService`가 받는 `ProductFinder`에 `@Lazy`가 붙는다. 타입은 like → product 한 방향이지만 빈은 순환한다. `ProductQueryService`는 `ProductInfoAssembler`를 거쳐 `LikeCounter`, 곧 `LikeQueryService`를 받고, `LikeQueryService`는 `ProductFinder`, 곧 `ProductQueryService`를 받는다.
- 같은 not-found 조회가 product, point, order의 Query Service와 Modify Service에 한 벌씩 있다. Finder가 `Info`를 돌려주므로 Modify 쪽이 엔티티를 얻으려면 직접 읽어야 하기 때문이다. 좋아요 누르기는 상품이 있는지만 보려고 `ProductFinder.find`를 불러 브랜드와 좋아요 수까지 읽으므로 쿼리가 둘 더 나간다. 둘 다 [#94](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/94)가 맡는다. 처음에는 [#93](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/93)의 일이었으나, Finder가 애그리거트를 통째로 돌려주면 둘 다 사라지므로 2026-10-08에 옮겼다. (2026-10-08, [ADR 0014](./0014-finders-load-whole-aggregates.md): 둘 다 풀렸다. Finder가 자기 애그리거트를 엔티티로 돌려주므로 없으면 던지는 조회는 Finder에만 있고, Modify Service가 그것을 부른다. 좋아요 누르기는 `LikeValidator`를 거쳐 상품만 읽는다.)
- `springdoc.use-fqn: true`라 `GET /v3/api-docs`의 스키마 이름이 옮겨진 패키지를 따른다. 응답은 `com.loopers.interfaces.api…`에서 `com.loopers.adapter.webapi…`로, Request는 `com.loopers.application.<개념>…`에서 `com.loopers.application.<개념>.provided…`로 바뀌었다. HTTP 계약에서 바뀐 것은 이것 하나이고, 생성된 문서를 단언하는 테스트는 없다.
- JPA 쿼리 애노테이션(`@Query`, `@EntityGraph`)이 `application/*/required`의 포트에 있다. 3주차의 잠금(`@Lock`, `@Modifying`)도 그 포트에 붙는다. `application`이 Spring Data와 JPA를 알게 된 것이다. `domain`은 여전히 Spring을 들이지 않는다.
- `provided`/`required` 쓰임을 막는 ArchUnit 규칙은 없다. 다른 조각의 `required` 저장소나 구현 클래스를 쓰지 않는다는 것은 splearn처럼 리뷰가 지킨다.
- 과정 제출의 기준(`upstream/giwankim`)과의 차이가 커졌다. `upstream/giwankim..refactor/architecture`는 채택 전 173개 파일이었고 지금은 286개다.

## 대체하는 결정

- [카탈로그 설계 5.20](../design/catalog.md)의 선택 B(domain의 저장 약속을 infrastructure의 `<X>JpaRepository`와 `<X>RepositoryImpl`이 구현한다). 저장소는 이제 `required`의 Spring Data 인터페이스다. 5.20의 대안 A와 같은 모양인데, 5.20이 적은 다시 볼 조건("domain이 Spring Data에 의존해도 된다고 정할 때") 때문이 아니다. 저장소가 `domain`을 떠나 `application`으로 왔다.
- [카탈로그 설계 5.21](../design/catalog.md)의 목록 조각 `PageSlice`와 그 자리 `domain/shared`. [#81](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/81)이 `PageSlice`를 이미 지웠다. 포트, Finder, 웹 어댑터의 목록 봉투가 Spring Data의 `Slice`를 쓰고, required 포트가 `Pageable`을 받는다.

이 결정은 다른 기록의 일부도 바꾼다. 카탈로그 설계 §1의 계층 구조, 5.16의 다시 볼 조건, 5.17과 5.18의 자리, 5.31의 이유, 포인트·주문 설계 §7의 패키지와 확정의 조율, §15의 차감 경로, §16.2의 자리가 그렇다. 해당 절은 원문을 그대로 두고, 날짜 메모에 무엇이 바뀌고 무엇이 그대로인지 적었다. 이름만 바뀐 자리는 그 자리에서 고쳤다. [ADR 0003](./0003-confirm-order-in-one-transaction.md)과 [ADR 0007](./0007-fixtures-build-through-constructors.md)의 결정은 그대로이고 날짜 메모만 달았다.

## 다시 보는 조건

리뷰가 `provided`/`required` 쓰임의 위반을 놓친 일이 생기면 그 쓰임을 막는 ArchUnit 규칙을 다시 본다.
