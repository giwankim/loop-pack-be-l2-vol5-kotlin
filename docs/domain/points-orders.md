# 포인트·주문 도메인 — 규칙, 속성, 행위

개념의 뜻은 [`CONTEXT.md`](../../CONTEXT.md)에 있고 여기서는 반복하지 않는다. 표기와 예외 규칙은 [카탈로그 도메인](./catalog.md)과 같다. 구조와 API, 결정은 [`docs/design/points-orders.md`](../design/points-orders.md)에 있다.

2026-09-18 기준 충전과 잔액 조회(#12), 확정 전 주문 생성과 내 상세 조회(#13), 원자적 주문 확정과 결제 이력(#14), 내 주문 목록 조회(#15), 관리자 조회(#16)까지 구현되었다. 2026-09-28 충전·주문 생성의 `Idempotency-Key`와 성공 재생을 걷어 냈다. 요청마다 새 충전·새 주문이며, 이미 확정된 주문의 재확정은 거절한다([ADR 0005](../adr/0005-no-request-idempotency-until-a-retrying-caller.md), 설계 17). 같은 날 포인트 이력도 걷어 냈다. 충전은 잔액만 바꾸고, 결제의 기록은 확정된 주문이다([ADR 0006](../adr/0006-no-point-history-until-a-reader.md), 설계 18).

## 포인트 계정 (PointAccount)

애그리거트 루트. `BaseEntity`를 상속한다. 사용자를 객체로 참조하지만 사용자의 상태를 바꾸지 않는다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `user` | `User` | 계정의 사용자. `@OneToOne(fetch = LAZY)`, 읽기용. DB 외래 키의 자리(설계 12.1) |
| `userId` | `Long` | `user`의 식별자. 프록시가 들고 있어 사용자를 읽지 않는다 |
| `balance` | `Money` | 잔액. 처음 0원 |

테이블 `point_account`. `user_id` 유일(`uk_point_account_user_id`), `users`로 외래 키(`fk_point_account_user`). `deletedAt`은 상속하지만 쓰지 않는다. 계정을 지우는 유스케이스가 없다.

### 규칙

- 사용자마다 계정 하나. 사용자 fixture와 함께 만들고, 조회나 충전이 없는 계정을 만들어 주지 않는다. 사용자는 있는데 계정이 없으면 application이 `POINT_ACCOUNT_MISSING`(500)이다(설계 5.9, 6절 끝, 12.5).
- 잔액은 0 이상이다. `Money`가 지킨다. 상품 가격의 상한은 잔액에 적용되지 않고 `Long` 범위만 지킨다(설계 5.7).
- 충전액은 1원 이상이다. 어기면 `InvalidChargeAmountException`. 충전 후 잔액이 `Long` 범위를 넘으면 `InvalidMoneyException`. 어느 쪽이든 잔액은 그대로다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `PointAccount(user)` | 그 사용자의 0원 계정을 만든다 | 없음 |
| `charge(amount)` | 잔액에 `amount`를 더한다 | `InvalidChargeAmountException`, `InvalidMoneyException` |
| `pay(amount)` | 양의 결제액을 차감한다. 어느 주문의 결제인지는 모른다 | `InvalidPaymentAmountException`, `InsufficientPointsException`. 거절하면 잔액은 그대로다 |

### 저장 약속

`PointAccountRepository`: `save`, `findByUserId`. 없는 계정은 null이다.

### 협력

- 충전: `PointService.charge` → 요청자 존재 확인 → 계정 조회 → `account.charge(amount)`. `point_account` 한 행만 바뀐다(설계 18.3). 요청마다 새 충전이라 같은 충전액을 다시 보내면 다시 충전된다(설계 17).
- 잔액 조회: `PointService.findBalance` → 요청자 존재 확인 → 계정 조회 → 현재 잔액.

## 주문 (Order)

애그리거트 루트. `BaseEntity`를 상속하지 않는다. 카탈로그의 논리 삭제 행위를 물려받으면 주문을 지울 수 있게 되는데, 주문을 지우는 유스케이스가 없다(설계 13).

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `userId` | `Long` | 주문한 사용자. 스칼라 참조이며 객체 연관을 두지 않는다. DB 외래 키는 있다(설계 13) |
| `items` | `List<OrderLineItem>` | 주문 품목. `productId` 오름차순이며 상품별로 하나씩이다 |
| `totalAmount` | `Money` | 품목 금액의 합. 생성 후 바뀌지 않는다 |
| `status` | `OrderStatus` | `DRAFT` 또는 `CONFIRMED` |
| `paidAmount` | `Money?` | 확정으로 결제한 금액. `DRAFT`에는 없다 |
| `confirmedAt` | `Instant?` | 확정 시각. `DRAFT`에는 없다 |
| `createdAt` | `Instant` | 생성 시각. 마이크로초로 잘라 저장한다 |

테이블 `orders`. `users`로 외래 키(`fk_orders_user`), 조회용 인덱스 `idx_orders_user_created`·`idx_orders_created`. 총액이 양수인지, 상태와 결제 필드가 맞는지는 DB `CHECK`도 본다.

### 규칙

- 품목은 하나 이상이고 상품별로 하나씩이다. 어기면 `InvalidOrderException`.
- 생성 정보(품목의 상품·이름·단가·수량·금액과 총액, 생성 시각)는 생성 후 바뀌지 않는다(ADR 0002).
- `DRAFT`는 재고도 포인트도 건드리지 않는다. 재고 0이거나 잔액이 부족한 상태에서도 생성된다(설계 5.7, Q12).
- 총액은 품목 금액의 합이며 `Money`가 `Long` 범위를 지킨다. 넘치면 `InvalidMoneyException`이고 주문은 저장되지 않는다.
- `DRAFT`에는 `paidAmount`·`confirmedAt`이 없고 `CONFIRMED`에는 둘 다 있으며 `paidAmount`는 총액과 같다. 확정 전 주문만 확정할 수 있다. 이미 확정된 주문은 `OrderAlreadyConfirmedException`으로 거절해 결제액·확정 시각을 다시 쓰지 않는다.
- 생성 시각은 MySQL `datetime(6)`과 정밀도를 맞춰 생성 응답과 저장 후 조회가 같은 값을 준다. `updatedAt`에 기대지 않는다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `Order(userId, products)` | 상품별 품목과 총액을 가진 `DRAFT`를 만든다 | `InvalidOrderException`, `InvalidMoneyException` |
| `validateConfirmable()` | 확정할 수 있는 주문인지 본다. 상태를 바꾸지 않는다 | `OrderAlreadyConfirmedException`. application이 상품·재고·포인트를 보기 전에 불러 이 거절이 앞서게 한다(설계 17.2) |
| `confirm()` | 저장된 총액과 마이크로초 정밀도의 현재 시각으로 확정한다 | `OrderAlreadyConfirmedException`. `validateConfirmable()`과 같은 검사다. 재고·잔액의 확보는 application의 같은 트랜잭션이 조율한다 |

### 저장 약속

`OrderRepository`: `save`, `findById`, `findByIdAndUserId`, `findAll(userId, page, size)`. 없으면 null이다. 주문을 지우는 약속은 없다.

`findById`는 소유자를 묻지 않으므로 관리자 조회만 쓴다. `findAll`은 한 조각을 최신순으로 주며 만든 시각이 같으면 나중에 받은 식별자가 앞선다. `userId`가 있으면 그 사용자의 주문만, 없으면 모든 사용자의 주문을 본다. 내 목록과 관리자 목록이 이 하나를 쓴다(설계 16.1). 조각에 오른 주문의 품목은 조회가 함께 읽어 주므로 읽기 트랜잭션을 벗어난 뒤에도 품목이 실려 있다.

### 협력

- 생성: `OrderService.create` → 요청자 존재 확인 → 입력 정규화(상품별 수량 합산·정렬) → 상품·브랜드 확인 후 이름·단가를 읽어 저장. 주문과 품목은 한 트랜잭션이다. 요청마다 새 주문이라 같은 품목을 다시 보내면 `DRAFT`가 하나 더 생긴다(설계 17).
- 상세 조회: `OrderService.find` → 요청자 존재 확인 → `findByIdAndUserId` → 없거나 남의 주문이면 `ORDER_NOT_FOUND`(404). 저장된 스냅샷만 읽고 현재 상품을 읽지 않는다.
- 목록 조회: `OrderService.findAll(userId, OrderListRequest)` → 요청자 존재 확인 → `findAll(userId, …)` → 조각의 항목을 읽기 트랜잭션 안에서 `OrderInfo`로 옮긴다. 상세와 같은 스냅샷을 최신순으로 주고, 남의 주문은 오르지 않는다(설계 14).
- 확정: `OrderService.confirm` → 요청자·소유권 확인 → `Order.validateConfirmable`(이미 확정이면 `ORDER_ALREADY_CONFIRMED` 409) → 모든 품목의 상품·브랜드 확인 → 상품별 `Product.deductStock` → `PointAccount.pay(총액)` → `Order.confirm`. 재고·잔액·주문의 변경은 커밋에서 함께 나가는 하나의 트랜잭션이며 실패 시 같은 DRAFT를 유지한다. 가격은 저장된 총액을 사용한다. 동시 요청의 경합 처리는 범위 밖이며, 같은 주문의 동시 확정도 막지 않는다(ADR 0002·0003, 설계 18.2).
- 관리자 상세 조회: `OrderService.findForAdmin` → `findById` → 없으면 `ORDER_NOT_FOUND`(404). 요청자 헤더도 소유권도 없다. 자격은 관리자 경계가 본다.
- 관리자 목록 조회: `OrderService.findAll(OrderAdminListRequest)` → 페이지 범위 확인 → 같은 `findAll(userId, …)`에 거를 사용자를 넣거나 비운다. 주문한 사용자의 식별자를 응답에 싣고, 카탈로그가 바뀌거나 상품이 삭제되어도 저장된 이름·단가를 그대로 준다(설계 16).

## 주문 품목 (OrderLineItem)

주문이 소유한다. `BaseEntity`를 상속하지 않고 생성자가 `internal`이라 `Order`만 만든다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `order` | `Order` | 속한 주문. `@ManyToOne(fetch = LAZY)`. DB 외래 키의 자리 |
| `productId` | `Long` | 대상 상품. 스칼라 참조이며 객체 연관을 두지 않는다. DB 외래 키는 있다(Q14, Q21) |
| `productName` | `String` | 생성 당시의 상품 이름 |
| `unitPrice` | `Money` | 생성 당시의 상품 가격. 양수 |
| `quantity` | `Int` | 합산한 구매 수량. 양수 |
| `lineAmount` | `Money` | `unitPrice` × `quantity` |

테이블 `order_line_item`. `(order_id, product_id)` 유일(`uk_order_line_item_product`), `orders`로 외래 키(`fk_order_line_item_order`), `product`로 외래 키(`fk_order_line_item_product`). 단가·수량·금액이 양수인지는 DB `CHECK`도 본다.

### 규칙

- 이름과 단가는 생성 당시의 값이다. 이후 상품의 이름·가격이 바뀌어도 이 품목은 그대로다(ADR 0002).
- 상품을 객체로 참조하지 않는 까닭은 `Product`의 `@SQLRestriction`이 join에도 붙어 논리 삭제된 상품의 주문을 읽을 수 없게 되기 때문이다(설계 12.1). 상품이 삭제되어도 주문과 그 스냅샷은 읽힌다.
- 같은 상품의 입력 수량은 합산해 한 품목으로 남긴다. 음수·0인 입력을 합산으로 감출 수 없다(설계 5.2).
- 합산 수량은 양의 `Int` 범위, 금액은 `Long` 범위다. 넘치면 거절하고 주문의 일부만 저장하지 않는다.

## 사용자 (User)와 요청자

카탈로그 도메인의 규칙이 그대로다. 포인트 충전·잔액 조회와 주문 생성·확정·상세·목록 조회 모두 요청자가 있어야 하며, 헤더의 존재는 interfaces(`UserIdHeader`)가, 사용자의 존재는 application(`PointService`·`OrderService`)이 본다. 요청자는 자기 잔액과 자기 주문만 다룬다. 서비스가 받는 사용자 식별자는 요청자 하나뿐이라 남의 잔액을 부를 길이 없고, 주문 조회는 `findByIdAndUserId`와 요청자를 넣은 `findAll(userId, …)`로 요청자의 것만 읽는다. 목록 조회는 관리자와 하나를 쓰지만 고객 경로가 넣는 사용자 식별자는 요청자뿐이다. 남의 주문과 없는 주문은 같은 404다(설계 5.9).
