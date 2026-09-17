# 카탈로그 도메인 — 규칙, 속성, 행위

개념의 뜻은 [`CONTEXT.md`](../../CONTEXT.md)에 있고 여기서는 반복하지 않는다. 이 문서는 각 개념이 무엇을 가지고, 무엇을 지키고, 무엇을 할 수 있는지를 적는다. 구조와 API는 [`docs/design/catalog.md`](../design/catalog.md)에 있다.

표기: 속성은 코드 이름, 규칙은 어기면 거절되는 조건, 행위는 공개 메서드다. 거절은 상태를 바꾸지 않는다. 도메인 규칙의 거절은 `RuleViolationException`(`com.loopers.domain`)의 하위 예외로 나타내고, 인터페이스 계층이 400 `BAD_REQUEST`와 예외 메시지로 옮긴다. 도메인은 `ErrorType`이나 HTTP를 모른다. 저장소를 봐야 하는 거절(중복, 없음, 삭제 조건)은 application이 `CoreException(ErrorType)`로 나타낸다.

이름 규칙: application 계층의 유스케이스 컴포넌트는 `Service` 접미사를 쓰고 `Facade`는 쓰지 않는다(`BrandService`, `ProductService`, `LikeService`). domain 계층에는 `Service`를 붙인 클래스를 두지 않는다.

## 브랜드 (Brand)

애그리거트 루트. `BaseEntity`를 상속한다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자. `BaseEntity` |
| `name` | `Name` | 이름 |
| `deletedAt` | `ZonedDateTime?` | 삭제 시각. `BaseEntity` |

### 규칙

- 이름은 `Name`의 규칙을 따른다. 어기면 `InvalidNameException`.
- 삭제되지 않은 브랜드끼리는 이름이 같을 수 없다. 같은지는 대소문자를 가리지 않고 본다(`Loopers`와 `loopers`는 같은 이름). 이 규칙은 저장소를 봐야 하므로 브랜드 자신이 아니라 application이 등록·수정 전에 확인한다. 어기면 `BRAND_NAME_DUPLICATED`.
- 삭제되지 않은 상품이 하나라도 남아 있으면 삭제할 수 없다. 재고 0인 상품도 남은 상품이다. 이것도 application이 상품 저장소에 물어 확인한다. 어기면 `BRAND_HAS_PRODUCTS`.
- 삭제된 브랜드는 조회·수정·삭제·상품 등록의 대상이 아니다. 되돌리지 않는다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `Brand(name)` | 검사를 거친 `Name`으로 만든다 | 없음. 이름 규칙은 `Name`이 본다 |
| `update(name)` | 이름을 바꾼다 | `InvalidNameException` |
| `delete()` | `deletedAt`을 찍는다. `BaseEntity`의 멱등 삭제 | 없음. 삭제 조건은 호출 전에 application이 본다 |

### 협력

- 등록: `BrandService.register` → `Brand(Name(name))`(이름 규칙 검사와 trim) → 뗀 이름으로 중복 조회 → 저장. 중복 조회가 trim된 이름을 봐야 하므로 이름 규칙이 먼저다.
- 삭제: `BrandService.delete` → 살아 있는 브랜드 조회 → 살아 있는 상품이 있는지 조회 → `brand.delete()` → 저장.

## 상품 (Product)

애그리거트 루트. `BaseEntity`를 상속한다. 브랜드를 객체로 참조하지만 브랜드의 상태를 바꾸지 않는다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `brand` | `Brand` | 속한 브랜드. `@ManyToOne(fetch = LAZY)`, 읽기용 |
| `name` | `Name` | 이름 |
| `price` | `Money` | 가격 |
| `stock` | `Stock` | 재고. `@Embedded` |
| `deletedAt` | `ZonedDateTime?` | 삭제 시각 |

### 규칙

- 이름은 브랜드와 같은 `Name` 규칙이다. 어기면 `InvalidNameException`.
- 가격은 1원 이상 1,000,000,000원 이하다. `Money`가 음수를 막고 `Product`가 1원 이상과 상한을 막는다. 어기면 `InvalidPriceException`.
- 브랜드는 만들 때 정해지고 바뀌지 않는다. 수정 메서드에 브랜드 인자가 없다.
- 등록할 때 브랜드는 존재하고 삭제되지 않은 것이어야 한다. application이 브랜드를 조회해 넘긴다. 없으면 `BRAND_NOT_FOUND`.
- 삭제된 상품은 고객·관리자 조회, 수정, 재고 변경, 새 좋아요의 대상이 아니다. 남은 좋아요는 그대로 두고 취소만 허용한다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `Product(brand, name, price, stock)` | 가격 범위를 검사하고 만든다. 이름·재고는 값 객체가 이미 검사했다 | `InvalidPriceException` |
| `update(name, price)` | 이름과 가격을 바꾼다. 하나라도 어기면 둘 다 그대로다 | `InvalidNameException`, `InvalidPriceException` |
| `updateStock(quantity)` | 재고를 최종 수량 `Stock(quantity)`로 바꾼다 | `InvalidStockException` |
| `isSoldOut()` | 재고가 0이면 참 | 없음 |
| `delete()` | `deletedAt`을 찍는다 | 없음 |

`decrease`는 이 조각에 없다. 주문 확정이 들어올 때 `Stock`에 더한다.

### 협력

- 관리자 재고 변경: `ProductService.updateStock` → 살아 있는 상품 조회 → `product.updateStock(quantity)` → 저장.
- 고객 상세: `ProductService.getProduct` → 살아 있는 상품 조회(브랜드 포함) → 좋아요 수 조회 → `ProductInfo.Customer`. `soldOut`은 `product.isSoldOut()`에서 온다.

## 이름 (Name)

값 객체. `@Embeddable`, 불변. `domain/shared`에 있고 브랜드와 상품이 함께 쓴다. 쓰는 엔티티가 `name` 컬럼에 담는다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `value` | `String` | 앞뒤 공백을 뗀 이름 |

### 규칙

- 앞뒤 공백을 뗀 뒤 비어 있지 않고 100자 이하다. 어기면 `InvalidNameException`.
- 뗀 값이 같으면 같은 이름이다.

## 재고 (Stock)

값 객체. `@Embeddable`, 불변. 상품의 일부이며 식별자가 없다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `quantity` | `Int` | 남은 수량 |

### 규칙

- 0 이상이다. 음수로 만들 수 없다. 어기면 `InvalidStockException`.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `Stock(quantity)` | 수량을 검사하고 만든다 | `InvalidStockException` |
| `isEmpty()` | 수량이 0이면 참 | 없음 |

TDD 대표 사례: `Stock(-1)`은 거절되고, `Stock(0)`은 허용되며, `Product.updateStock(-1)`을 거절한 뒤 기존 재고가 그대로인지 확인한다.

## 금액 (Money)

값 객체. `@Embeddable`, 불변. 연산은 새 값을 돌려준다. `domain/shared`에 있다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `amount` | `Long` | 원 단위 정수 |

### 규칙

- 0 이상이다. 어기면 `InvalidPriceException`(이 조각에서 금액이 쓰이는 곳이 가격뿐이라서다. 포인트가 들어오면 금액 자체의 예외로 나눈다).
- 더하기·곱하기 결과가 `Long` 범위를 넘으면 `InvalidPriceException`으로 거절한다. `Math.addExact`, `Math.multiplyExact`.
- 가진 값보다 큰 값을 뺄 수 없다. 어기면 `InvalidPriceException`.

### 행위

| 메서드 | 하는 일 |
| --- | --- |
| `plus(other)` | 더한 새 값 |
| `minus(other)` | 뺀 새 값. 결과가 음수면 거절 |
| `times(count)` | 수량을 곱한 새 값 |
| `compareTo(other)` | 크기 비교 |

이 조각에서는 `plus`, `minus`, `times`를 부르는 곳이 없다. 포인트와 주문에서 쓴다. 지금 두는 이유는 가격의 타입을 처음부터 금액으로 고정해 나중에 컬럼 매핑을 바꾸지 않기 위해서다.

## 좋아요 (Like)

사용자–상품 관계. `BaseEntity`를 상속하되 `delete()`를 쓰지 않고 행을 지운다(ADR 0001).

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `userId` | `Long` | 누른 사용자 |
| `productId` | `Long` | 대상 상품 |

DB 유일 제약: `(user_id, product_id)`.

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
| `LikeService.like(userId, productId)` | 살아 있는 상품 조회 → 관계가 있으면 끝 → 없으면 `Like` 저장 |
| `LikeService.unlike(userId, productId)` | 관계를 찾아 있으면 행 삭제 → 없으면 끝. 상품 존재는 보지 않는다 |
| `LikeService.getMyLikes(userId, page)` | 사용자의 관계를 최신순으로 → 살아 있는 상품만 골라 상품 항목으로 조합 |

## 사용자 (User)와 요청자

`User`는 실습용 데이터다. 이 조각에서 사용자를 만들거나 바꾸는 API는 없다. 요청자는 `X-USER-ID` 헤더에서 온 사용자 식별자다.

### 규칙

- 좋아요 누르기·취소·내 목록은 요청자가 있어야 한다. 헤더가 없거나 그 사용자가 없으면 `UNAUTHORIZED`.
- 내 좋아요 목록의 path `userId`는 요청자와 같아야 한다. 다르면 `FORBIDDEN`.
- 브랜드·상품 조회는 요청자가 없어도 된다.

## 상품 목록 정렬 (ProductSort)

| 값 | API 값 | 정렬 |
| --- | --- | --- |
| `LATEST` | `latest` | `createdAt` 내림차순, 같으면 `id` 내림차순 |
| `PRICE_ASC` | `price_asc` | `price.amount` 오름차순, 같으면 `id` 내림차순 |
| `LIKES_DESC` | `likes_desc` | 좋아요 수 내림차순, 같으면 `id` 내림차순 |

모르는 값은 `INVALID_SORT`. 기본값은 `LATEST`.
