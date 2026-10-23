# 포인트·주문 도메인 — 규칙, 속성, 행위

개념의 뜻은 [`CONTEXT.md`](../../CONTEXT.md)에 있고 여기서는 반복하지 않는다. 표기와 예외 규칙은 [카탈로그 도메인](./catalog.md)과 같다. 구조와 API, 결정은 [`docs/design/points-orders.md`](../design/points-orders.md)에 있다.

2026-09-18 기준 충전과 잔액 조회(#12), 확정 전 주문 생성과 내 상세 조회(#13), 원자적 주문 확정과 결제 이력(#14), 내 주문 목록 조회(#15), 관리자 조회(#16)까지 구현되었다. 2026-09-28 충전·주문 생성의 `Idempotency-Key`와 성공 재생을 걷어 냈다. 요청마다 새 충전·새 주문이며, 이미 확정된 주문의 재확정은 거절한다([ADR 0005](../adr/0005-no-request-idempotency-until-a-retrying-caller.md), 설계 17). 같은 날 포인트 이력도 걷어 냈다. 충전은 잔액만 바꾸고, 결제의 기록은 확정된 주문이다([ADR 0006](../adr/0006-no-point-history-until-a-reader.md), 설계 18). 2026-10-09 확정은 주문 행과 포인트 계정 행을, 충전은 포인트 계정 행을 처음 읽을 때 잠근다([ADR 0019](../adr/0019-order-and-point-account-rows-locked-on-first-read.md), 설계 19). 2026-10-22 확정은 PG로 결제하고([ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md)), 포인트로 결제하는 길은 지웠다. 충전과 잔액 조회는 그대로이고, 포인트로 확정된 이전 주문은 그대로 읽힌다([ADR 0027](../adr/0027-point-payment-path-is-deleted.md)).

## 포인트 계정 (PointAccount)

애그리거트 루트. `BaseEntity`를 상속한다. 사용자를 식별자로만 가리키고 사용자의 상태를 바꾸지 않는다.

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `userId` | `Long` | 계정의 사용자. 연관이 아닌 스칼라 열이다. 계정을 읽는 쪽이 사용자를 건너 읽지 않는다(설계 12.1, [ADR 0014](../adr/0014-finders-load-whole-aggregates.md)) |
| `balance` | `Money` | 잔액. 처음 0원 |

테이블 `point_account`. `user_id` 유일(`UK_POINT_ACCOUNT_ON_USER_ID`), `users`로 외래 키(`FK_POINT_ACCOUNT_ON_USER_ID`). 연관이 없으므로 외래 키는 `scalar-foreign-keys.sql`이 만든다. 계정을 지우는 유스케이스는 없지만 애그리거트 루트라 논리 삭제가 기본이다. `@SQLRestriction`이 삭제된 계정을 가리므로 삭제된 계정은 없는 계정이다([ADR 0016](../adr/0016-every-entity-extends-base-entity-and-roots-soft-delete.md)).

### 규칙

- 사용자마다 계정 하나. 사용자 fixture와 함께 만들고, 조회나 충전이 없는 계정을 만들어 주지 않는다. 사용자는 있는데 계정이 없으면 application이 `POINT_ACCOUNT_MISSING`(500)이다(설계 5.9, 6절 끝, 12.5).
- 잔액은 0 이상이다. `Money`가 지킨다. 상품 가격의 상한은 잔액에 적용되지 않는다. `BigInteger`로 계산하고 `DECIMAL(65,0)`으로 저장한다.
- 충전액은 1원 이상이다. 어기면 `InvalidChargeAmountException`. `Long` 범위를 넘는 잔액도 허용한다. 거절된 충전은 잔액을 바꾸지 않는다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `PointAccount(userId)` | 그 사용자의 0원 계정을 만든다 | 없음 |
| `charge(amount)` | 잔액에 `amount`를 더한다 | `InvalidChargeAmountException` |

잔액을 줄이는 `pay(amount)`는 포인트로 결제하는 길과 함께 지웠다([ADR 0027](../adr/0027-point-payment-path-is-deleted.md)).

### 저장 약속

`PointAccountRepository`: `save`, `findByUserId`, `findForUpdateByUserId`. 없는 계정은 null이다. `findForUpdateByUserId`는 계정 행을 `FOR UPDATE`로 잠가 읽고, 충전만 쓴다(ADR 0019, ADR 0027).

### 협력

- 충전: `PointCharger.charge` → `PointAccountFinder.findForUpdate`로 계정을 잠가 읽는다 → `account.charge(amount)` → 저장. `point_account` 한 행만 바뀐다(설계 19.2). 같은 계정의 다른 충전과 겹치면 계정 행의 잠금으로 한쪽이 커밋할 때까지 기다리고, 뒤에 온 쪽은 앞선 쪽이 커밋한 잔액에 더한다([ADR 0019](../adr/0019-order-and-point-account-rows-locked-on-first-read.md)). 확정은 계정을 바꾸지 않으므로 계정 행에서 겨루는 것은 충전끼리뿐이다([ADR 0027](../adr/0027-point-payment-path-is-deleted.md)). 요청마다 새 충전이라 같은 충전액을 다시 보내면 다시 충전된다(설계 17).
- 잔액 조회: `PointAccountFinder.findByUser` → 계정 조회 → 계정을 돌려주고 응답이 현재 잔액을 고른다. 잠그지 않는다.

## 주문 (Order)

애그리거트 루트. `BaseEntity`를 상속한다. 주문을 지우는 유스케이스는 없지만 루트라 논리 삭제가 기본이다. `@SQLRestriction`이 삭제된 주문을 가리므로 삭제된 주문은 없는 주문이다([ADR 0016](../adr/0016-every-entity-extends-base-entity-and-roots-soft-delete.md)).

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자. `BaseEntity` |
| `userId` | `Long` | 주문한 사용자. 스칼라 참조이며 객체 연관을 두지 않는다. DB 외래 키는 있다(설계 13) |
| `lineItems` | `List<OrderLineItem>` | 주문 품목. `productId` 오름차순이며 상품별로 하나씩이다. 읽기 전용이며, 매핑되는 쪽은 `private`인 `_lineItems`다 |
| `totalAmount` | `Money` | 품목 금액의 합. 열이 없고 읽을 때 계산한다(설계 5.6). 품목이 바뀌지 않으므로 생성 후 바뀌지 않는다 |
| `status` | `OrderStatus` | `DRAFT`(확정 전), `PAYMENT_PENDING`(결제 중), `CONFIRMED`(확정), `PAYMENT_FAILED`(결제 실패) |
| `paymentMethod` | `PaymentMethod?` | 결제수단. `POINT` 또는 `PG`이고 `DRAFT`에는 없다. 결제 중·결제 실패는 늘 `PG`다 |
| `paidAmount` | `Money?` | 결제액. 열이 없고 읽을 때 계산한다. `CONFIRMED`면 총액, 아니면 없다 |
| `paysThroughPg` | `Boolean` | 결제수단이 `PG`인지. 결제 애그리거트는 이런 주문에만 있다(ADR 0028 규칙 1). 열이 없고 읽을 때 계산한다 |
| `productQuantities` | `Map<Long, Int>` | 상품 ID마다 품목의 수량. 확정이 이것으로 재고를 예약하고, 결제가 실패하면 같은 수량을 돌려준다(ADR 0026 규칙 2). 열이 없고 읽을 때 계산한다 |
| `confirmedAt` | `Instant?` | 확정 시각. `CONFIRMED`에만 있다 |
| `createdAt` | `Instant` | 생성 시각. `BaseEntity`가 저장할 때 마이크로초로 잘라 찍는다 |

테이블 `orders`. `users`로 외래 키(`FK_ORDERS_ON_USER_ID`), 조회용 인덱스 `IDX_ORDERS_ON_USER_ID_AND_CREATED_AT_AND_ID`·`IDX_ORDERS_ON_CREATED_AT_AND_ID`. 상태와 결제수단·확정 시각이 맞는지는 DB `CHECK`(`CK_ORDERS_ON_PAYMENT_STATE`)도 본다. `DRAFT`는 둘 다 없고, `PAYMENT_PENDING`·`PAYMENT_FAILED`는 결제수단이 `PG`이고 확정 시각이 없으며, `CONFIRMED`는 둘 다 있다. 결제액 열과 그 양수 조건(`CK_ORDERS_ON_PAID_AMOUNT_POSITIVE`)은 2026-10-22에 지웠다. 품목이 생성 뒤 바뀌지 않으므로 그 열이 담던 값은 늘 확정된 주문의 총액이었다([이행 SQL](../migrations/2026-10-22-order-payment.sql)).

### 규칙

- 품목은 하나 이상이고 상품별로 하나씩이다. 어기면 `InvalidOrderException`.
- 생성 정보(품목의 상품·이름·단가·수량·금액과 총액, 생성 시각)는 생성 후 바뀌지 않는다(ADR 0002).
- `DRAFT`는 재고도 포인트도 건드리지 않는다. 재고 0이거나 잔액이 부족한 상태에서도 생성된다(설계 5.7, Q12).
- 총액은 품목 금액의 합이며 `Money`가 임의 정밀도로 계산한다. `Long` 범위를 넘는 품목 금액과 총액도 허용한다.
- `CONFIRMED`에만 `paidAmount`·`confirmedAt`이 있고 `paidAmount`는 총액과 같다. 확정 전 주문만 결제를 시작할 수 있다. 이미 확정된 주문은 `OrderAlreadyConfirmedException`으로, 결제를 시작한 주문(`PAYMENT_PENDING`·`PAYMENT_FAILED`)은 `OrderStatusConflictException`으로 거절해 결제수단·확정 시각을 다시 쓰지 않는다.
- 결제를 끝내거나 실패시키는 것은 결제 중인 주문만 할 수 있다. 다른 상태는 `OrderStatusConflictException`으로 거절한다. `PAYMENT_FAILED`는 끝난 상태다.
- 생성 시각은 MySQL `datetime(6)`과 정밀도를 맞춰 생성 응답과 저장 후 조회가 같은 값을 준다. `updatedAt`에 기대지 않는다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `Order(userId, products)` | 상품별 품목과 총액을 가진 `DRAFT`를 만든다 | `InvalidOrderException` |
| `ensureDraft()` | 확정 전(DRAFT) 주문인지 본다. 상태를 바꾸지 않는다 | `CONFIRMED`면 `OrderAlreadyConfirmedException`, `PAYMENT_PENDING`·`PAYMENT_FAILED`면 `OrderStatusConflictException`. application이 상품·재고를 보기 전에 불러 이 거절이 앞서게 한다(설계 17.2) |
| `ensurePaymentPending()` | 결제 중(PAYMENT_PENDING) 주문인지 본다. 상태를 바꾸지 않는다 | `OrderStatusConflictException` |
| `startPayment()` | 결제수단 `PG`를 적고 결제 중으로 바꾼다 | `ensureDraft()`와 같다 |
| `completePayment()` | 마이크로초 정밀도의 현재 시각으로 확정한다 | `ensurePaymentPending()`과 같다 |
| `failPayment()` | 결제에 실패한 주문으로 바꾼다 | `ensurePaymentPending()`과 같다 |

포인트로 결제해 바로 확정하던 `confirm()`은 포인트로 결제하는 길과 함께 지웠다([ADR 0027](../adr/0027-point-payment-path-is-deleted.md)). 결제수단이 `POINT`인 주문에 이르는 전이는 없고, 그런 주문은 그 전에 확정된 행으로만 있다.

### 저장 약속

`OrderRepository`: `save`, `findWithLineItemsById`, `findWithLineItemsByIdAndUserId`, `findForUpdateWithLineItemsByIdAndUserId`. 단건 조회는 품목을 함께 읽고, 없으면 null이다([ADR 0014](../adr/0014-finders-load-whole-aggregates.md)). `findForUpdateWithLineItemsByIdAndUserId`는 품목을 읽는 그 조회가 주문 행을 `FOR UPDATE`로 잠그고, 확정만 쓴다(ADR 0019). 목록은 `OrderListRepository`의 `findAll(userId, pageable)`이다. 주문을 지우는 약속은 없다.

`findWithLineItemsById`는 소유자를 묻지 않으므로 관리자 조회만 쓴다. `findAll`은 한 조각을 최신순으로 주며 만든 시각이 같으면 나중에 받은 식별자가 앞선다. `userId`가 있으면 그 사용자의 주문만, 없으면 모든 사용자의 주문을 본다. 내 목록과 관리자 목록이 이 하나를 쓴다(설계 16.1). 조각에 오른 주문의 품목은 조회가 함께 읽어 주므로 읽기 트랜잭션을 벗어난 뒤에도 품목이 실려 있다.

### 협력

- 생성: `OrderCreator.create` → 받은 품목마다 삭제되지 않은 상품인지 확인하고 이름·단가를 읽는다(`ProductFinder.findOrNull`. 브랜드는 보지 않는다. 브랜드 삭제가 상품을 함께 삭제하기 때문이다) → `Order`가 상품 중복을 거절하고 상품 ID 순으로 품목을 둔 뒤 저장. 주문과 품목은 한 트랜잭션이다. 판매할 수 없는 상품이 중복으로 오면 상품 확인의 `ORDER_PRODUCT_NOT_AVAILABLE`(404)이 중복의 400보다 먼저다(설계 15). 요청마다 새 주문이라 같은 품목을 다시 보내면 `DRAFT`가 하나 더 생긴다(설계 17).
- 상세 조회: `OrderFinder.find` → `findWithLineItemsByIdAndUserId`로 주문과 품목을 한 번에 읽는다 → 없거나 남의 주문이면 `ORDER_NOT_FOUND`(404). 저장된 스냅샷만 읽고 현재 상품을 읽지 않는다. 잠그지 않는다.
- 목록 조회: `OrderFinder.findAll(userId, OrderListRequest)` → `findAll(userId, …)` → 조각의 주문과 품목을 읽기 트랜잭션 안에서 읽어 `Order`로 돌려준다. 상세와 같은 스냅샷을 최신순으로 주고, 남의 주문은 오르지 않는다(설계 14).
- 확정: `OrderConfirmer.confirm` → `OrderFinder.findForUpdate`로 요청자의 주문을 품목과 함께 잠가 읽는다(소유권 확인. 잠금은 그 조회 자체에 걸린다) → `Order.ensureDraft`(이미 확정이면 `ORDER_ALREADY_CONFIRMED` 409) → 품목 전부의 상품 ID별 수량으로 `StockDeductor.deduct`를 한 번 부른다(→ 모든 상품 행을 상품 ID 순으로 잠가 읽고(`ProductFinder.findForUpdateOrNull`. 브랜드는 조인하지 않는다. 브랜드 삭제가 상품을 함께 삭제하기 때문이다) → 하나라도 없거나 삭제됐으면 차감 전에 `PRODUCT_NOT_FOUND`로 거절하고, 확정이 그것을 `ORDER_PRODUCT_NOT_AVAILABLE`(404)로 옮긴다 → 상품마다 `Product.deductStock` → 하나라도 품절이 되면 고객 상품 목록이 바뀌었다고 표시 → 저장) → `PointDeductor.deduct`(→ `PointAccountFinder.findForUpdate`로 계정을 잠가 읽음 → `PointAccount.pay(총액)` → 저장) → `Order.confirm` → 저장. 두 차감 포트는 상품·포인트 쪽의 것이고 확정의 트랜잭션에 참여한다. 재고·잔액·주문의 변경은 하나의 트랜잭션으로 커밋되며 실패 시 같은 DRAFT를 유지한다. 차감이 표시했으면 주문·상품·포인트 계정의 UPDATE가 모두 나간 뒤, 커밋 바로 앞의 마지막 SQL로 상품 목록 revision을 올린다. 잠금은 주문 → 상품 → 포인트 계정 → 상품 목록 revision의 차례다([ADR 0020](../adr/0020-product-list-cache-keyed-by-a-db-revision-locked-last.md)). 확정은 표시하지 않는다. 가격은 품목에 저장된 단가로 센 총액을 사용한다.
  - 잠금은 주문 → 상품(id 오름차순) → 포인트 계정 차례로 걸린다. 같은 주문의 확정끼리는 주문 행에서 기다린다. 뒤진 쪽은 잠금을 얻은 뒤 가장 최근에 커밋된 주문을 읽으므로, 앞선 확정이 커밋했으면 상품과 계정을 읽기 전에 `ORDER_ALREADY_CONFIRMED`(409)로 거절된다([ADR 0019](../adr/0019-order-and-point-account-rows-locked-on-first-read.md)).
  - 상품을 바꾸는 다른 쓰기(관리자의 수정·재고 수정·삭제, 브랜드 삭제)나 같은 상품의 다른 확정과는 상품 행에서([ADR 0018](../adr/0018-pessimistic-row-locks-in-one-global-order.md)), 같은 사용자의 충전이나 다른 확정의 결제와는 계정 행에서 한쪽이 커밋할 때까지 기다린다. 3초 안에 잠금을 얻지 못하면 `CONCURRENT_REQUEST`(409)이고 아무것도 바뀌지 않는다.

  > 2026-10-22 위 확정의 흐름은 포인트로 결제하던 때의 원문이다. 확정은 이제 PG로 결제하며 TX A(주문을 잠가 읽기 → 재고 예약 → `Order.startPayment` → 결제 시작), 트랜잭션 밖의 PG 호출, TX B(주문을 잠가 읽기 → PG의 답을 결제에 반영 → 성공이면 `Order.completePayment`)로 나뉜다([ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md), [ADR 0028](../adr/0028-order-slice-orchestrates-the-payment.md)). `PointDeductor.deduct`, `PointAccount.pay`, `Order.confirm`은 지웠고 확정은 포인트 계정을 읽지도 잠그지도 않는다([ADR 0027](../adr/0027-point-payment-path-is-deleted.md)). 잠금은 TX A가 주문 → 상품(id 오름차순) → 상품 목록 revision(품절이 바뀌면)이다(ADR 0028 규칙 4). TX B는 지금 주문만 잠근다([ADR 0019](../adr/0019-order-and-point-account-rows-locked-on-first-read.md) 규칙 3의 메모). 실패한 결제의 예약을 돌려주게 되면 ADR 0028 규칙 4대로 그 경우에만 상품과 revision도 잠근다.

  > 2026-10-22 TX A는 주문을 잠가 읽은 뒤 재고를 예약하기 전에 멱등 키를 본다([ADR 0025](../adr/0025-confirm-requires-an-idempotency-key-and-replays-stored-state.md)). 포인트로 확정된 주문은 `ORDER_ALREADY_CONFIRMED`(409)다. 결제가 있는 주문은 결제를 시작한 키면 PG를 부르지 않고 저장된 상태로 답하고(`PaymentFinder.findByOrder`), 다른 키면 `IDEMPOTENCY_KEY_MISMATCH`(409)다. 확정 전 주문은 다른 결제가 쓴 키면 `IDEMPOTENCY_KEY_IN_USE`(409)다(`PaymentValidator.validateForStart`). 그래서 위 둘째 항목의 뒤진 확정은 이제 거절되지 않고, 같은 키면 재요청으로 답한다. 같은 키로 겨룬 두 확정 전 주문 가운데 진 쪽은 결제 삽입이 `UK_PAYMENT_ON_IDEMPOTENCY_KEY`에 걸려 예약과 함께 되돌아가고, 조율하는 Service가 트랜잭션 밖에서 키를 다시 보아 같은 409로 답한다.

  > 2026-10-23 TX B는 결제가 실패로 끝나면 `Order.failPayment` 뒤에 `StockRestorer.restore`로 예약을 한 번 돌려주며, 그때만 상품(id 오름차순)과 상품 목록 revision도 잠근다(ADR 0028 규칙 4). 위 첫 메모의 "TX B는 지금 주문만 잠근다"는 그 전의 것이다. 대사(`OrderPaymentReconciler.reconcile`)는 주문을 잠그지 않고 읽은 뒤 PG에 조회하고, 찾은 PG 거래를 같은 TX B로 반영한다. 확정과 대사의 흐름, 잠금, 장면은 [결제 설계](../design/payments.md) 10–14절에 있다.

  > 2026-10-23 위 흐름과 메모의 `StockDeductor.deduct`와 `StockRestorer.restore`는 이제 한 포트 `StockReserver`의 `reserve`와 `release`이고, 대사의 `OrderPaymentReconciler.reconcile`은 `OrderConfirmer.reconcile`이다([ADR 0013](../adr/0013-commerce-api-follows-splearn-hexagonal-structure.md)의 2026-10-23 메모).
- 관리자 상세 조회: `OrderFinder.findForAdmin` → `findWithLineItemsById` → 없으면 `ORDER_NOT_FOUND`(404). 요청자 헤더도 소유권도 없다. 자격은 관리자 경계가 본다.
- 관리자 목록 조회: `OrderFinder.findAll(OrderAdminListRequest)` → 페이지 범위 확인 → 같은 `findAll(userId, …)`에 거를 사용자를 넣거나 비운다. 주문한 사용자의 식별자를 응답에 싣고, 카탈로그가 바뀌거나 상품이 삭제되어도 저장된 이름·단가를 그대로 준다(설계 16).

## 주문 품목 (OrderLineItem)

주문이 소유한다. `BaseEntity`를 상속하고 생성자가 `internal`이라 `Order`만 만든다. 주문을 거쳐서만 읽고 `delete()`를 부르지 않는다. 삭제 필터도 두지 않는다. 주문이 품목을 조인으로 읽을 때 품목의 필터도 붙어, 걸러진 품목이 주문에서도 총액에서도 빠지기 때문이다([ADR 0016](../adr/0016-every-entity-extends-base-entity-and-roots-soft-delete.md)).

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자. `BaseEntity` |
| `order` | `Order` | 속한 주문. `@ManyToOne(fetch = LAZY)`. DB 외래 키의 자리 |
| `productId` | `Long` | 대상 상품. 스칼라 참조이며 객체 연관을 두지 않는다. DB 외래 키는 있다(Q14, Q21) |
| `productName` | `String` | 생성 당시의 상품 이름 |
| `unitPrice` | `Money` | 생성 당시의 상품 가격. 양수 |
| `quantity` | `Int` | 구매 수량. 양수 |
| `lineAmount` | `Money` | 품목 금액. `unitPrice` × `quantity`. 열이 없고 읽을 때 계산한다(설계 5.6) |

테이블 `order_line_item`. `(order_id, product_id)` 유일(`UK_ORDER_LINE_ITEM_ON_ORDER_ID_AND_PRODUCT_ID`), `orders`로 외래 키(`FK_ORDER_LINE_ITEM_ON_ORDER_ID`), `product`로 외래 키(`FK_ORDER_LINE_ITEM_ON_PRODUCT_ID`)와 그 외래 키가 쓰는 인덱스 `IDX_ORDER_LINE_ITEM_ON_PRODUCT_ID`. 단가가 양수인지(`CK_ORDER_LINE_ITEM_ON_UNIT_PRICE_POSITIVE`), 수량이 양수인지(`CK_ORDER_LINE_ITEM_ON_QUANTITY_POSITIVE`)는 DB `CHECK`도 본다.

### 규칙

- 이름과 단가는 생성 당시의 값이다. 이후 상품의 이름·가격이 바뀌어도 이 품목은 그대로다(ADR 0002).
- 상품을 객체로 참조하지 않는 까닭은 `Product`의 `@SQLRestriction`이 join에도 붙어 논리 삭제된 상품의 주문을 읽을 수 없게 되기 때문이다(설계 12.1). 상품이 삭제되어도 주문과 그 스냅샷은 읽힌다.
- 한 주문 안에서 상품 하나는 품목 하나로만 나타난다. 같은 상품을 두 번 담은 요청은 `Order`의 검사가 `InvalidOrderException`(400)으로 거절하고 아무것도 저장하지 않는다(설계 5.2).
- 수량은 양의 `Int` 범위다. 금액은 `BigInteger`로 계산하고, 저장되는 단가는 `DECIMAL(65,0)`의 65자리 한도를 따른다.

## 사용자 (User)와 요청자

카탈로그 도메인의 규칙이 그대로다. 포인트 충전·잔액 조회와 주문 생성·확정·상세·목록 조회 모두 요청자가 있어야 하며, 헤더와 사용자의 존재는 웹 경계(`@RequesterId`)가 본다. application은 받은 사용자 식별자를 믿는다([ADR 0015](../adr/0015-web-boundary-accepts-the-requester.md)). 요청자는 자기 잔액과 자기 주문만 다룬다. 서비스가 받는 사용자 식별자는 요청자 하나뿐이라 남의 잔액을 부를 길이 없고, 주문 조회는 `findWithLineItemsByIdAndUserId`와 요청자를 넣은 `findAll(userId, …)`로 요청자의 것만 읽는다. 목록 조회는 관리자와 하나를 쓰지만 고객 경로가 넣는 사용자 식별자는 요청자뿐이다. 남의 주문과 없는 주문은 같은 404다(설계 5.9).
