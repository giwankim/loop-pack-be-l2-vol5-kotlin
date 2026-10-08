# 카탈로그 도메인 — 규칙, 속성, 행위

개념의 뜻은 [`CONTEXT.md`](../../CONTEXT.md)에 있고 여기서는 반복하지 않는다. 이 문서는 각 개념이 무엇을 가지고, 무엇을 지키고, 무엇을 할 수 있는지를 적는다. 구조와 API는 [`docs/design/catalog.md`](../design/catalog.md)에 있다.

표기: 속성은 코드 이름, 규칙은 어기면 거절되는 조건, 행위는 공개 메서드다. 거절은 상태를 바꾸지 않는다. 도메인 규칙의 거절은 `RuleViolationException`(`com.loopers.domain.shared`)의 하위 예외로 나타내고, 웹 어댑터의 `ApiControllerAdvice`가 400 `BAD_REQUEST`와 예외 메시지로 옮긴다. 도메인은 `ErrorType`이나 HTTP를 모른다. 저장소를 봐야 하는 거절(중복, 없음, 삭제 조건)은 application이 `CoreException(ErrorType)`로 나타낸다.

이름 규칙: application 계층의 유스케이스는 각 개념의 provided 포트로 드러난다. 읽기는 개념마다 Finder 하나(`BrandFinder`, `ProductFinder`, `LikeFinder`)이고, 쓰기는 부르는 쪽에 따라 나눈다(관리자의 `BrandRegister`·`ProductRegister`, 고객의 `Liker`, 주문 확정이 부르는 `StockDeductor`). 포트는 개념마다 `<개념>QueryService`와 `<개념>ModifyService`가 구현한다([ADR 0013](../adr/0013-commerce-api-follows-splearn-hexagonal-structure.md)). `Facade`는 쓰지 않는다. domain 계층에는 `Service`를 붙인 클래스를 두지 않는다.

## 브랜드 (Brand)

애그리거트 루트. `BaseEntity`를 상속한다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자. `BaseEntity` |
| `name` | `String` | 받은 그대로의 이름 |
| `deletedAt` | `Instant?` | 삭제 시각. `BaseEntity` |

### 규칙

- 이름은 공백뿐일 수 없고, 앞뒤 공백을 포함해 100자(`Brand.NAME_MAX_LENGTH`) 이하다. 받은 그대로 저장한다. 어기면 `InvalidNameException`.
- 삭제되지 않은 브랜드끼리는 이름이 같을 수 없다. 같은지는 컬럼 collation이 정한다. 대소문자와 뒤 공백은 가리지 않고(`Loopers`, `loopers`, `Loopers `는 같은 이름), 앞 공백은 가린다(` Loopers`는 다른 이름). 이 규칙은 저장소를 봐야 하므로 브랜드 자신이 아니라 application이 등록·수정 전에 확인한다. 수정은 자기를 뺀 나머지 브랜드와 본다(대소문자만 바꾸는 수정이 자기 이름과 겹치지 않도록). 어기면 `BRAND_NAME_DUPLICATED`.
- 삭제되지 않은 상품이 하나라도 남아 있으면 삭제할 수 없다. 재고 0인 상품도 남은 상품이다. 이것도 application이 상품 저장소에 물어 확인한다. 어기면 `BRAND_HAS_PRODUCTS`.
- 삭제된 브랜드는 조회·수정·삭제·상품 등록의 대상이 아니다. 되돌리지 않는다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `Brand(name)` | 이름의 공백과 길이 상한을 검사하고 받은 그대로 담아 만든다 | `InvalidNameException` |
| `update(name)` | 이름을 바꾼다. 거절되면 기존 이름이 그대로 남는다 | `InvalidNameException` |
| `delete()` | `deletedAt`을 찍는다. `BaseEntity`의 멱등 삭제 | 없음. 삭제 조건은 호출 전에 application이 본다 |

### 협력

- 등록: `BrandRegister.register` → `Brand(name)`(공백, 길이 상한 검사) → `BrandValidator.validateForRegister`가 받은 이름으로 중복 조회 → 저장. 공백뿐이거나 너무 긴 이름은 조회 없이 거절된다.
- 수정: `BrandRegister.update` → 삭제되지 않은 브랜드 조회 → `BrandValidator.validateForUpdate`가 받은 이름을 자기 말고 다른 브랜드가 쓰는지 조회 → `brand.update(name)`(공백, 길이 상한 검사) → 저장. 중복 거절은 브랜드를 바꾸기 전에 끝나므로 거절된 이름은 브랜드에 닿지 않는다.
- 목록: `BrandFinder.findAll` → 삭제되지 않은 브랜드를 최신 등록순(등록 시각 내림차순, 동률은 id 내림차순)으로 한 조각. 총 개수는 세지 않는다.
- 삭제: `BrandRegister.delete` → 삭제되지 않은 브랜드 조회 → `BrandValidator.validateForDelete`가 `ActiveProductChecker.hasActiveProducts`로 남은 상품이 있는지 조회(상품의 `ProductFinder`가 `ProductRepository.existsByBrandId`로 답한다) → 있으면 `BRAND_HAS_PRODUCTS`로 거절 → `brand.delete()` → 저장. 거절이 `brand.delete()` 앞에 있어야 거절된 브랜드에 삭제 시각이 찍히지 않는다.

## 상품 (Product)

애그리거트 루트. `BaseEntity`를 상속한다. 브랜드를 객체로 참조하지만 브랜드의 상태를 바꾸지 않는다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `brand` | `Brand` | 속한 브랜드. `@ManyToOne(fetch = LAZY)`, 읽기용 |
| `name` | `String` | 받은 그대로의 이름 |
| `price` | `Money` | 가격 |
| `stock` | `Int` | 재고. 팔 수 있는 남은 수량 |
| `deletedAt` | `Instant?` | 삭제 시각 |

### 규칙

- 이름은 공백뿐일 수 없고, 앞뒤 공백을 포함해 100자(`Product.NAME_MAX_LENGTH`) 이하다. 받은 그대로 저장한다. 브랜드와 상한이 같은 것은 우연이라 따로 바뀔 수 있다. 어기면 `InvalidNameException`.
- 가격은 1원 이상 1,000,000,000원 이하다. `Money`가 음수를 막고(`InvalidMoneyException`) `Product`가 1원 이상과 상한을 막는다(`InvalidPriceException`).
- 재고는 0 이상이다. 만들 때와 재고 변경이 음수를 막는다. 어기면 `InvalidStockException`. 차감은 0 이하의 수량을 `InvalidStockException`으로, 남은 재고보다 큰 수량을 `InsufficientStockException`으로 막는다. 거절하면 기존 재고를 유지한다.
- 브랜드는 만들 때 정해지고 바뀌지 않는다. 수정 메서드에 브랜드 인자가 없다.
- 등록할 때 브랜드는 존재하고 삭제되지 않은 것이어야 한다. application이 브랜드를 조회해 넘긴다. 없으면 `BRAND_NOT_FOUND`.
- 삭제된 상품은 고객·관리자 조회, 수정, 재고 변경, 새 좋아요의 대상이 아니다. 남은 좋아요는 그대로 두고 취소만 허용한다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `Product(brand, name, price, stock)` | 이름의 공백·길이 상한, 가격 범위, 재고 하한을 검사하고 이름은 받은 그대로 담아 만든다 | `InvalidNameException`, `InvalidPriceException`, `InvalidStockException` |
| `update(name, price)` | 이름과 가격을 바꾼다. 하나라도 어기면 둘 다 그대로다 | `InvalidNameException`, `InvalidPriceException` |
| `updateStock(quantity)` | 재고를 최종 수량으로 바꾼다. 음수면 기존 재고를 유지한다 | `InvalidStockException` |
| `deductStock(quantity)` | 양수 구매 수량을 차감한다. 거절하면 기존 재고를 유지한다 | 0 이하이면 `InvalidStockException`, 부족하면 `InsufficientStockException` |
| `isSoldOut()` | 재고가 0이면 참 | 없음 |
| `delete()` | `deletedAt`을 찍는다 | 없음 |

주문 확정에서는 주문의 `OrderConfirmer`가 상품의 `StockDeductor.deduct`를 거쳐 `deductStock`을 부른다. 다른 재고·포인트·주문 변경과 같은 트랜잭션이다(ADR 0003).

### 협력

- 관리자 재고 변경: `ProductRegister.updateStock` → `ProductFinder.find`로 삭제되지 않은 상품 조회 → `product.updateStock(quantity)` → 저장.
- 고객 상세: `ProductFinder.findInfo` → `find`로 삭제되지 않은 상품 조회 → 브랜드 이름과 좋아요 수 조회 → `ProductInfo`. 상품 하나를 엔티티로 주는 `find`는 브랜드도 좋아요 수도 읽지 않는다([ADR 0014](../adr/0014-finders-load-whole-aggregates.md)). `soldOut`은 `product.isSoldOut()`에서 온다. 고객 DTO가 `stock`을 버리고 `soldOut`을 고른다.

TDD 대표 사례: 재고 -1로 만든 `Product`는 거절되고, 0은 허용되며, `updateStock(-1)`을 거절한 뒤 기존 재고가 그대로인지 확인한다. 재고는 값 객체가 아니라 `Product`의 `Int`다(설계 5.35).

## 금액 (Money)

값 객체. `@Embeddable`, 불변. 연산은 새 값을 돌려준다. `domain/shared`에 있다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `amount` | `Long` | 원 단위 정수 |

### 규칙

- 0 이상이다. 어기면 `InvalidMoneyException`. 가격 범위는 `Product`가 따로 막고 `InvalidPriceException`을 던진다. 금액은 여러 개념이 함께 쓰므로 가격 예외를 빌리지 않는다.
- 더하기·곱하기 결과가 `Long` 범위를 넘으면 `InvalidMoneyException`으로 거절한다. `Math.addExact`, `Math.multiplyExact`.
- 가진 값보다 큰 값을 뺄 수 없다. 어기면 `InvalidMoneyException`.

### 행위

| 메서드 | 하는 일 |
| --- | --- |
| `plus(other)` | 더한 새 값 |
| `minus(other)` | 뺀 새 값. 결과가 음수면 거절 |
| `times(count)` | 수량을 곱한 새 값 |
| `compareTo(other)` | 크기 비교 |

이 조각에서는 `plus`, `minus`, `times`를 부르는 곳이 없다. 포인트와 주문에서 쓴다. 지금 두는 이유는 가격의 타입을 처음부터 금액으로 고정해 나중에 컬럼 매핑을 바꾸지 않기 위해서다.

## 좋아요 (Like)

사용자–상품 관계. `BaseEntity`를 상속하되 `delete()`를 쓰지 않고 행을 지운다(ADR 0001). 논리 삭제가 기본인 애그리거트 루트 가운데 하나뿐인 예외다([ADR 0016](../adr/0016-every-entity-extends-base-entity-and-roots-soft-delete.md)).

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `userId` | `Long` | 누른 사용자. 자연 식별자 |
| `productId` | `Long` | 대상 상품. 자연 식별자 |

좋아요의 정체는 사용자–상품 쌍이다. `userId`·`productId`는 Hibernate의 `@NaturalId`로 표시하며 바뀌지 않는다. 저장 약속은 자연 식별자로 읽지 않고 파생 조회를 쓴다. DB 유일 제약은 `UK_LIKES_USER_ID_PRODUCT_ID` `(user_id, product_id)` 하나다.

### 규칙

- 같은 사용자–상품 쌍은 하나만 있다. 이미 있으면 새로 만들지 않고 성공으로 답한다.
- 없는·삭제된 상품에는 새로 누를 수 없다. `PRODUCT_NOT_FOUND`.
- 취소는 자기 관계만 없앤다. 관계가 없어도 성공으로 답한다. 상품이 삭제되었어도 취소된다.
- 좋아요 수는 이 관계를 세어 구한다. 상품에 저장하지 않는다.

### 행위

| 메서드 | 하는 일 |
| --- | --- |
| `Like(userId, productId)` | 관계를 만든다 |

행동은 관계 자체보다 유스케이스에 있다.

| 유스케이스 | 흐름 |
| --- | --- |
| `Liker.like(userId, productId)` | `LikeValidator.validateForLike`가 `ProductFinder.find`로 삭제되지 않은 상품 조회 → 관계가 있으면 끝 → 없으면 `Like` 저장 |
| `Liker.unlike(userId, productId)` | 관계를 찾아 있으면 행 삭제 → 없으면 끝. 상품 존재는 보지 않는다 |
| `LikeFinder.findLikedProducts(userId, request)` | 요청자가 누른 삭제되지 않은 상품을 최근에 누른 순으로 한 조각 조회 → 조각의 상품마다 좋아요 수를 한 번에 세어 상품 항목으로 조합 |

### 저장 약속

`LikeRepository`: `save`, `existsByUserIdAndProductId`, `findByUserIdAndProductId`, `delete`(행 삭제), `countByProductId`, `findProductLikeCounts`(식별자 목록의 그룹 집계. 좋아요가 없는 상품은 행이 없다).

좋아요 목록은 `LikeRepository`가 아니라 상품의 `ProductRepository.findAllLikedBy(userId, pageable)`가 돌려주고, `LikeFinder`는 그것을 `ProductFinder.findAllLikedBy`로 받는다. 돌려주는 것이 상품이고, 삭제된 상품을 조회가 걸러야 조각의 크기와 `hasNext`가 남은 상품만 세기 때문이다(설계 5.29).

### 협력

- 상품 상세·수정·재고 변경: `ProductFinder`·`ProductRegister`가 상품을 읽은 뒤 상품이 선언한 `LikeCounter`에 좋아요 수를 묻고 `ProductInfo`에 싣는다. `LikeFinder`가 `countByProductId`로 세어 답한다. 등록은 새 상품에 좋아요가 없으므로 세지 않고 0이다.
- 상품 목록: 조각의 상품 식별자 목록을 `LikeCounter`에 한 번 묻는다. `LikeFinder`가 `findProductLikeCounts` 한 번으로 세고, 좋아요가 없는 상품은 0으로 채운다. 항목마다 세지 않는다(설계 5.28).
- 내 좋아요 목록: `LikeFinder`가 `ProductFinder.findAllLikedBy`로 상품 조각을 받는다. `ProductFinder`가 같은 방법으로 좋아요 수를 세어 상품 항목을 채운다. 차례는 좋아요를 누른 시각이고, 같으면 나중에 누른 쪽이 앞선다.

## 사용자 (User)와 요청자

`User`는 실습용 데이터다. 이 조각에서 사용자를 만들거나 바꾸는 API는 없다. 요청자는 `X-USER-ID` 헤더에서 온 사용자 식별자다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자. `BaseEntity` |

테이블 `users`. 식별자 말고 속성이 없다(설계 5.27). 사용자를 지우는 API는 없지만 애그리거트 루트라 논리 삭제가 기본이다. `@SQLRestriction`이 삭제된 사용자를 가리므로 삭제된 사용자는 없는 사용자다([ADR 0016](../adr/0016-every-entity-extends-base-entity-and-roots-soft-delete.md)). 신원 서비스의 사용자를 이 서비스가 들고 있는 사본이다. 외래 키가 가리키고, 요청자를 받아들일 때 "이 사용자가 있는가"에 답한다([ADR 0015](../adr/0015-web-boundary-accepts-the-requester.md)). 저장 약속 `UserRepository`는 `save`와 `existsById`뿐이다.

### 규칙

- 좋아요 누르기·취소·내 목록은 요청자가 있어야 한다. 헤더가 없거나 그 사용자가 없으면 `UNAUTHORIZED`. 둘 다 웹 경계(`@RequesterId`)가 컨트롤러 앞에서 보고, 사용자의 존재는 `UserFinder.exists`에 묻는다. application은 받은 사용자 식별자를 믿는다([ADR 0015](../adr/0015-web-boundary-accepts-the-requester.md)).
- 요청자는 자기 좋아요만 다룬다. 좋아요의 경로(`/api/v1/likes`)는 사용자를 품지 않고 요청자만 헤더에서 오므로, 남의 좋아요를 가리킬 길도 그것을 거절할 `FORBIDDEN`도 없다(설계 5.36). application에는 요청자만 넘어간다(설계 5.30).
- 브랜드·상품 조회는 요청자가 없어도 된다.

## 상품 목록 정렬 (ProductSort)

| 값 | API 값 | 정렬 |
| --- | --- | --- |
| `LATEST` | `latest` | `createdAt` 내림차순, 같으면 `id` 내림차순 |
| `PRICE_ASC` | `price_asc` | `price.amount` 오름차순, 같으면 `id` 내림차순 |
| `LIKES_DESC` | `likes_desc` | 좋아요 수 내림차순, 같으면 `id` 내림차순 |

모르는 값은 `INVALID_SORT`. 기본값은 `LATEST`.
