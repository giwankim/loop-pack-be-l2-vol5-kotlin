---
status: accepted
date: 2026-10-08
---

# Finder는 애그리거트를 통째로 읽고, 여러 애그리거트를 합친 응답만 Info가 된다

[카탈로그 설계 5.7](../design/catalog.md)은 Service가 엔티티를 돌려줄지 `Info`를 돌려줄지를 지연 로딩으로 갈랐다. 연관을 건너 읽으면 `Info`다. `open-in-view`가 꺼져 있어 트랜잭션 밖의 지연 로딩이 실패하기 때문이다. 이 기준은 한 애그리거트 안의 `Order` → `OrderLineItem`을 두 애그리거트를 건너는 `Product` → `Brand`와 같은 칸에 넣는다. 그래서 주문·포인트·상품의 Finder는 `Info`를 돌려줬고, 엔티티가 필요한 Modify Service는 저장소를 직접 읽었다. 같은 not-found 조회가 조각마다 두 벌 있었다([ADR 0013](./0013-commerce-api-follows-splearn-hexagonal-structure.md)의 대가). 좋아요 누르기는 상품이 있는지만 보려고 `ProductFinder.find`를 불러 브랜드와 좋아요 수까지 읽었다.

[splearn](https://github.com/tobyspringboot/splearn-1-part2/tree/2d1acdffdd0ad2aa4d400d0ed84544fd07ca0401)(`2d1acdf`)은 선을 애그리거트에 긋고, 두 물음을 가른다.

- **"무엇인가."** `CurriculumRepository`에는 지연 `findById`와, `@EntityGraph(attributePaths = {"sections", "sections.lessons"})`로 애그리거트를 통째로 읽는 `findWithSectionsById`가 있다. `CurriculumFinder`가 둘을 `find`·`findWithSections`로 내준다. 둘 다 엔티티를 돌려주고 없으면 던진다.
- **"해도 되는가."** `CourseModifyService`가 강의를 바꾸기 전에 `CourseValidator`가 사전 조건을 본다. 구현은 `CourseValidationService`이고, 다른 조각에는 포트로 묻는다.

저장소는 null을 돌려주고, 던지는 것은 application Service의 `find`다. 이것을 따라 규칙 넷을 둔다.

1. **Finder의 `find`는 자기 애그리거트를 통째로 읽고, 없으면 던진다.** 저장소 메서드는 애그리거트 안의 연관을 `@EntityGraph`로 함께 읽는다. 그래서 트랜잭션이 끝난 뒤 지연 로딩할 것이 남지 않는다. provided 포트의 읽기가 null을 돌려줄 수 있으면 이름에 `…OrNull`을 붙인다.
2. **`Info`는 응답이 여러 애그리거트를 합칠 때만 둔다.** 브랜드 이름과 좋아요 수를 싣는 `ProductInfo`가 그렇다. 쓰기 포트는 자기 애그리거트를 돌려준다. 예외는 `ProductRegister`다. 관리자 응답이 그 조합을 쓰므로 계속 `ProductInfo`를 돌려준다.
3. **다른 애그리거트로 가는 연관은 읽기가 실제로 건널 때만 둔다.** 브랜드 이름을 fetch join으로 읽는 `Product.brand`가 그렇다. 외래 키를 얻으려고만 연관을 두지는 않는다. 스칼라 식별자의 외래 키는 `scalar-foreign-keys.sql`이 만든다([ADR 0015](./0015-web-boundary-accepts-the-requester.md)).
4. **저장소나 다른 조각이 있어야 하는 사전 조건은 Validator가 본다.** provided 포트 `<X>Validator`를 `<X>ValidationService`가 구현한다. 첫 거절에서 `CoreException`을 던지고 멈추므로 HTTP 응답은 그대로다. 예외가 하나 있다. 유스케이스가 쓸 데이터를 만들어 내는 검사는 Modify Service에 남는다. 주문의 "주문할 수 있는 상품"이 그렇다. 도메인 규칙은 계속 엔티티에 있다.

조각마다 이렇게 바뀐다.

- **주문.** `OrderRepository`의 조회는 `findWithLineItemsByIdAndUserId`와 `findWithLineItemsById`다. 둘 다 `@EntityGraph(attributePaths = ["lineItems"])`로 품목을 함께 읽는다. 품목 없는 주문을 원하는 호출자가 없어서 지연 짝은 두지 않는다. `OrderFinder`, `OrderCreator`, `OrderConfirmer`가 `Order`를 돌려주고 `OrderInfo`는 지운다. 목록은 `QuerydslOrderListRepository`가 이미 품목까지 읽는다. `OrderModifyService.confirm`은 `OrderFinder.find`로 주문을 얻는다.
- **포인트.** `PointAccount.user` 연관은 스칼라 `userId`가 되고, `FK_POINT_ACCOUNT_USER`는 같은 이름으로 스크립트로 옮긴다. `PointAccountFinder.findByUser(userId): PointAccount`가 `findBalance`를 대신한다. `PointCharger.charge`는 `PointAccount`를 돌려주고 `PointAccountInfo`는 지운다. `PointModifyService`는 `findByUser`로 계정을 얻는다.
- **상품.** `ProductFinder.find(id): Product`는 브랜드를 지연으로 둔 채 상품만 읽는다. `ProductInfo`를 주는 상세는 `findInfo(id)`가 되고, 주문이 부르는 `findOrderable`은 `findOrderableOrNull`이 된다. `ProductModifyService`는 `find`로 상품을 얻는다.
- **좋아요.** `LikeValidator.validateForLike(productId)`를 `LikeValidationService`가 `ProductFinder.find`로 구현한다. 그래서 좋아요 누르기의 쿼리가 둘 준다. `Like`는 스칼라 식별자를 그대로 쓴다.
- **브랜드.** `BrandValidator`의 `validateForRegister`, `validateForUpdate`, `validateForDelete`를 `BrandValidationService`가 구현한다. 이름 중복은 `BrandRepository`에, 남은 상품은 `ActiveProductChecker`에 묻는다. `BrandModifyService`에는 단계의 차례만 남는다. 어느 거절이 먼저인지는 바뀌지 않는다.

## 고르지 않은 것

- **5.7의 지연 로딩 기준을 지킨다.** 그러면 Modify Service가 저장소를 직접 읽는 두 번째 조회가 남고, 좋아요의 존재 확인이 좋아요 수까지 센다. 그 기준으로는 애그리거트 안의 연관과 밖의 연관을 가를 수 없다.
- **상품 읽기를 포트 둘로 나눈다.** 엔티티를 주는 포트와 `ProductInfo`를 주는 포트다. 그러면 조각마다 Finder가 하나라는 ADR 0013의 모양이 깨지고, `ActiveProductChecker`를 어느 쪽이 상속할지도 새로 정해야 한다. splearn의 `CurriculumFinder`도 Finder 하나가 깊이가 다른 두 읽기를 함께 낸다.
- **요청자만 확인하는 Validator를 조각마다 둔다.** 이 결정을 잡을 때는 application이 요청자를 확인했다. ADR 0015가 그 확인을 웹 경계로 옮겨서 이 대안은 필요가 없어졌다.
- **`Like`에 연관을 둔다.** 그러면 좋아요를 만들 때 `User` 참조가 있어야 한다. 얻는 것은 스크립트의 외래 키가 이미 주는 것뿐이다. 상품을 객체로 가리키면 삭제된 상품의 좋아요를 취소할 수 없게 되는 문제도 다시 생긴다(카탈로그 설계 2).

## 대가

- 웹 어댑터가 분리된(detached) 엔티티를 쥔다. 웹 어댑터가 `order.confirm()` 같은 메서드를 불러도 아무것도 저장되지 않고 오류도 나지 않는다. splearn도 같은 대가를 받아들인다. 이 결정은 그것을 막는 장치를 두지 않는다.
- `ProductFinder.find`가 준 상품의 `brand`는 지연 프록시다. 트랜잭션 밖에서 브랜드 이름을 읽으면 실패한다. 브랜드를 건너는 읽기는 `findInfo`, 목록들, `findOrderableOrNull`이다.
- 응답 DTO가 엔티티에서 바로 옮긴다(`OrderResponse.from(order)`). 엔티티의 프로퍼티 이름이 바뀌면 응답 DTO도 고친다. 전에는 그 사이에 `Info`가 있었다. HTTP 계약은 응답 DTO의 명시적 필드 선택과 HTTP 테스트가 지킨다.
- 검사가 하나뿐인 Validator(`LikeValidator`)는 얇다. 어느 조각이든 사전 조건이 같은 자리에 있다는 값으로 받아들인다.

## 다시 볼 조건

웹 어댑터가 분리된 엔티티를 바꾸는 실수가 리뷰를 지나가면, 포트가 엔티티 대신 읽기 전용 모양을 돌려주는 것을 다시 본다.

## 대체하는 결정

카탈로그 설계 5.7에서 `Info`를 두는 기준. ADR 0013의 대가 중 "같은 not-found 조회가 한 벌씩 있다"는 항목은 이 결정으로 풀렸다. 포인트·주문 설계 12.1의 연관 선택은 스크립트의 외래 키로 바뀐다. 카탈로그 설계 5.7, 포인트·주문 설계 §7·§16.2·12.1, ADR 0012, ADR 0013은 원문을 그대로 두고 날짜 메모를 달았다.
