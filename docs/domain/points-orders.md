# 포인트·주문 도메인 — 규칙, 속성, 행위

개념의 뜻은 [`CONTEXT.md`](../../CONTEXT.md)에 있고 여기서는 반복하지 않는다. 표기와 예외 규칙은 [카탈로그 도메인](./catalog.md)과 같다. 구조와 API, 결정은 [`docs/design/points-orders.md`](../design/points-orders.md)에 있다.

2026-09-18 기준 충전과 잔액 조회(#12), 확정 전 주문 생성과 내 상세 조회(#13), 원자적 주문 확정과 결제 이력(#14), 내 주문 목록 조회(#15)까지 구현되었다. 관리자 조회는 #16에서 더한다.

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
| `charge(amount, chargeKey)` | 잔액에 `amount`를 더하고 그 충전의 CHARGE `PointHistory`를 돌려준다. 저장은 부르는 쪽이 한다 | `InvalidChargeAmountException`, `InvalidMoneyException` |
| `pay(amount, order)` | 양의 결제액을 차감하고 주문 참조·직후 잔액을 담은 PAYMENT `PointHistory`를 돌려준다. 주문은 변경하지 않는다 | `InvalidPaymentAmountException`, `InsufficientPointsException`. 거절하면 잔액은 그대로다 |

### 저장 약속

`PointAccountRepository`: `save`, `findByUserId`. 없는 계정은 null이다.

### 협력

- 충전: `PointService.charge` → 요청자 존재 확인 → 계정 조회 → 같은 충전 키의 성공 이력 조회 → 있으면 충전액을 견줘 첫 결과 재생(같음) 또는 `IDEMPOTENCY_KEY_CONFLICT`(다름) → 없으면 `account.charge(amount, chargeKey)` → 돌려받은 이력 저장. 잔액 변경과 이력 저장은 한 트랜잭션이다(설계 5.5).
- 잔액 조회: `PointService.findBalance` → 요청자 존재 확인 → 계정 조회 → 현재 잔액.

## 포인트 이력 (PointHistory)

`BaseEntity`를 상속한다. 남긴 뒤 바꾸지 않는 기록이며 별도 애그리거트가 아니다. `PointAccount.charge`·`pay`가 만든다(설계 12.6, 15).

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `account` | `PointAccount` | 잔액이 바뀐 계정. `@ManyToOne(fetch = LAZY)`, 읽기용. DB 외래 키의 자리 |
| `type` | `PointHistoryType` | 성공의 종류. `CHARGE` 또는 `PAYMENT` |
| `amount` | `Money` | 잔액을 바꾼 금액. 양수 |
| `balanceAfter` | `Money` | 이 기록 직후의 잔액. 뒤에 잔액이 바뀌어도 그대로다 |
| `chargeKey` | `String?` | CHARGE의 충전 키. 계정 안에서 유일, 대소문자 구분. PAYMENT에는 없다 |
| `order` | `Order?` | PAYMENT의 주문. 읽기용 LAZY 연관이며 주문당 유일하고 물리 FK를 가진다. CHARGE에는 없다 |

테이블 `point_history`. `(point_account_id, charge_key)` 유일(`uk_point_history_point_account_id_charge_key`), `point_account`로 외래 키(`fk_point_history_point_account`). `charge_key`는 `varchar(128) character set utf8mb4 collate utf8mb4_bin`이라 조회와 유일 제약이 대소문자를 구분한다(설계 12.2).

### 규칙

- 성공해서 잔액이 바뀐 기록만 남긴다. 실패한 시도와 성공의 재생은 기록을 늘리지 않는다(설계 5.4).
- 같은 계정에 같은 충전 키의 기록은 하나다. `Charge-A`와 `charge-a`는 다른 키다.
- 이력의 `balanceAfter`는 그 충전·결제 직후의 계정 잔액과 같다. 계정이 이력을 만들며 지킨다.
- PAYMENT는 `order_id` 유일 제약(`uk_point_history_order_id`)과 주문 FK(`fk_point_history_order`)를 가진다. 금액은 양수, 직후 잔액은 0 이상이며 CHARGE/PAYMENT별 키·주문 식별자 조건은 DB CHECK도 지킨다.

### 저장 약속

`PointHistoryRepository`: `save`, `findByAccountIdAndChargeKey`. 이력을 바꾸거나 지우는 약속은 없다.

## 충전 키 (chargeKey)

값이며 따로 타입을 두지 않는다. 허용 형식은 `domain.shared.IdempotencyKey.PATTERN` = `[A-Za-z0-9_-]{1,128}` 하나이며, 길이와 저장 열의 정의도 그 object에 있다(설계 12.2). 주문 생성 키와 공유한다.

- HTTP `Idempotency-Key` 헤더가 없거나 형식을 어기면 interfaces(`IdempotencyKeyHeader.require`)가 `INVALID_IDEMPOTENCY_KEY`(400)로 거절한다. 같은 형식을 `PointChargeRequest`의 `@Pattern`이 Service 입구에서 한 번 더 본다(설계 12.4).
- 공백을 떼거나 대소문자를 바꾸지 않는다.
- 범위는 사용자 + 작업 종류다. 다른 사용자의 같은 키, 같은 사용자의 충전과 주문 생성에 쓴 같은 키는 서로 무관하다. 충전의 키는 `point_history`에, 생성의 키는 주문에 남으므로 저장 위치가 다르다(ADR 0004).

## 주문 (Order)

애그리거트 루트. `BaseEntity`를 상속하지 않는다. 카탈로그의 논리 삭제 행위를 물려받으면 주문을 지울 수 있게 되는데, 주문을 지우는 유스케이스가 없다(설계 13).

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `userId` | `Long` | 주문한 사용자. 스칼라 참조이며 객체 연관을 두지 않는다. DB 외래 키는 있다(설계 13) |
| `creationKey` | `String` | 생성 키. 사용자 안에서 유일, 대소문자 구분 |
| `items` | `List<OrderLineItem>` | 주문 품목. `productId` 오름차순이며 상품별로 하나씩이다 |
| `totalAmount` | `Money` | 품목 금액의 합. 생성 후 바뀌지 않는다 |
| `status` | `OrderStatus` | `DRAFT` 또는 `CONFIRMED` |
| `paidAmount` | `Money?` | 확정으로 결제한 금액. `DRAFT`에는 없다 |
| `confirmedAt` | `Instant?` | 확정 시각. `DRAFT`에는 없다 |
| `createdAt` | `Instant` | 생성 시각. 마이크로초로 잘라 저장한다 |

테이블 `orders`. `(user_id, creation_key)` 유일(`uk_orders_user_creation_key`), `users`로 외래 키(`fk_orders_user`), 조회용 인덱스 `idx_orders_user_created`·`idx_orders_created`. `creation_key`는 충전 키와 같은 열 정의를 쓴다(`IdempotencyKey.COLUMN_DEFINITION`, 설계 12.2). 총액이 양수인지, 상태와 결제 필드가 맞는지는 DB `CHECK`도 본다.

### 규칙

- 품목은 하나 이상이고 상품별로 하나씩이다. 어기면 `InvalidOrderException`.
- 생성 정보(품목의 상품·이름·단가·수량·금액과 총액, 생성 시각, 생성 키)는 생성 후 바뀌지 않는다(ADR 0002).
- `DRAFT`는 재고도 포인트도 건드리지 않는다. 재고 0이거나 잔액이 부족한 상태에서도 생성된다(설계 5.7, Q12).
- 총액은 품목 금액의 합이며 `Money`가 `Long` 범위를 지킨다. 넘치면 `InvalidMoneyException`이고 주문은 저장되지 않는다.
- `DRAFT`에는 `paidAmount`·`confirmedAt`이 없고 `CONFIRMED`에는 둘 다 있으며 `paidAmount`는 총액과 같다. 이미 확정되면 결제액·확정 시각을 다시 쓰지 않는다.
- 생성 시각은 MySQL `datetime(6)`과 정밀도를 맞춰 첫 응답과 저장 후 재생이 같은 값을 준다. `updatedAt`에 기대지 않는다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `Order(userId, creationKey, products)` | 상품별 품목과 총액을 가진 `DRAFT`를 만든다 | `InvalidOrderException`, `InvalidMoneyException` |
| `confirm()` | 저장된 총액과 마이크로초 정밀도의 현재 시각으로 확정한다 | `InvalidOrderException`. 이미 확정된 주문은 거절해 결제액·확정 시각을 다시 쓰지 않는다. 재고·잔액의 확보는 application의 같은 트랜잭션이 조율한다 |

### 저장 약속

`OrderRepository`: `save`, `findById`, `findByIdAndUserId`, `findByUserIdAndCreationKey`, `findAll(userId, page, size)`. 없으면 null이다. 주문을 지우는 약속은 없다.

`findById`는 소유자를 묻지 않으므로 관리자 조회만 쓴다. `findAll`은 한 조각을 최신순으로 주며 만든 시각이 같으면 나중에 받은 식별자가 앞선다. `userId`가 있으면 그 사용자의 주문만, 없으면 모든 사용자의 주문을 본다. 내 목록과 관리자 목록이 이 하나를 쓴다(설계 16.1). 조각에 오른 주문의 품목은 조회가 함께 읽어 주므로 읽기 트랜잭션을 벗어난 뒤에도 품목이 실려 있다.

### 협력

- 생성: `OrderService.create` → 요청자 존재 확인 → 입력 정규화(상품별 수량 합산·정렬) → 같은 생성 키의 주문 조회 → 있으면 정규화한 의도를 견줘 최초 `DRAFT` 응답 재생(같음) 또는 `IDEMPOTENCY_KEY_CONFLICT`(다름) → 없으면 상품·브랜드 확인 후 이름·단가를 읽어 저장. 주문·품목·생성 키는 한 트랜잭션이다.
- 상세 조회: `OrderService.find` → 요청자 존재 확인 → `findByIdAndUserId` → 없거나 남의 주문이면 `ORDER_NOT_FOUND`(404). 저장된 스냅샷만 읽고 현재 상품을 읽지 않는다.
- 목록 조회: `OrderService.findAll(userId, OrderListRequest)` → 요청자 존재 확인 → `findAll(userId, …)` → 조각의 항목을 읽기 트랜잭션 안에서 `OrderInfo`로 옮긴다. 상세와 같은 스냅샷을 최신순으로 주고, 남의 주문은 오르지 않는다(설계 14).
- 확정: `OrderService.confirm` → 요청자·소유권 확인 → CONFIRMED면 저장된 결과 반환 → 모든 품목의 상품·브랜드 확인 → 상품별 `Product.deductStock` → `PointAccount.pay` → `Order.confirm` → PAYMENT 저장. 전부 하나의 트랜잭션이며 실패 시 같은 DRAFT를 유지한다. 가격은 저장된 총액을 사용한다. 동시 요청의 경합 처리는 범위 밖이다(ADR 0002·0003).
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

## 주문 생성 키 (creationKey)

충전 키와 같은 값이며 같은 형식·같은 열 정의를 쓴다(`domain.shared.IdempotencyKey`, 설계 12.2). 남는 자리만 다르다. 생성의 키는 주문에 남는다(ADR 0004).

- HTTP `Idempotency-Key` 헤더가 없거나 형식을 어기면 interfaces(`IdempotencyKeyHeader.require`)가 `INVALID_IDEMPOTENCY_KEY`(400)로 거절한다. 같은 형식을 `OrderService.create`의 `@Pattern`이 Service 입구에서 한 번 더 본다(설계 12.4).
- 같은 키의 비교 대상은 합산·정렬한 상품별 수량이다. 상품의 현재 가격·이름은 견주지 않는다. 같으면 최초 `DRAFT` 응답과 201을 재생하고, 다르면 `IDEMPOTENCY_KEY_CONFLICT`(409)다(설계 5.8).
- 저장된 주문이 이미 `CONFIRMED`여도 생성 재요청의 응답은 최초 `DRAFT`와 불변 생성 정보다. 현재 상태는 상세 조회로 본다(ADR 0004).
- 실패한 요청은 키를 쓰지 않는다. 성공한 키는 만료되지 않는다(Q15).

## 사용자 (User)와 요청자

카탈로그 도메인의 규칙이 그대로다. 포인트 충전·잔액 조회와 주문 생성·확정·상세·목록 조회 모두 요청자가 있어야 하며, 헤더의 존재는 interfaces(`UserIdHeader`)가, 사용자의 존재는 application(`PointService`·`OrderService`)이 본다. 요청자는 자기 잔액과 자기 주문만 다룬다. 서비스가 받는 사용자 식별자는 요청자 하나뿐이라 남의 잔액을 부를 길이 없고, 주문 조회는 `findByIdAndUserId`·`findAllByUserId`로 요청자의 것만 읽는다. 남의 주문과 없는 주문은 같은 404다(설계 5.9).
