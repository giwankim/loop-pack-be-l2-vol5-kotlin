---
status: accepted
date: 2026-10-22
---

# 포인트로 결제하는 길을 지운다

2단계에서 확정은 PG로 결제한다. 과제는 PG 주문에서 포인트를 한 번도 빼지 말고 포인트로 대신 결제하지도 말라고 하면서, 충전과 잔액 조회는 남기고 포인트로 확정된 이전 주문은 그대로 읽히게 하라고 한다. 포인트 결제의 코드는 결제수단으로 고르게 남기거나, 아무도 부르지 않는 채로 두거나, 지울 수 있다.

1. **지운다.** `PointDeductor`, `PointModifyService.deduct`, `PointAccount.pay`, `InsufficientPointsException`과 `INSUFFICIENT_POINTS`, 그리고 포인트 결제를 보던 테스트(`OrderConfirmerConcurrencyTest`의 포인트 장면 등)다. 테스트를 `@Disabled`로 남기지 않는다.
2. **충전과 잔액 조회는 그대로다.** 계정 잠금([ADR 0019](./0019-order-and-point-account-rows-locked-on-first-read.md) 규칙 1)은 지금 충전과 확정의 경쟁으로만 시험된다. 확정이 계정을 떠나므로, 함께 풀린 두 충전이 모두 잔액에 닿는지 보는 테스트를 더한다.
3. **포인트로 확정된 이전 주문은 그대로 읽힌다.** 결제수단 POINT, 결제액, 품목이 보이고, 다시 확정하면 409 `ORDER_ALREADY_CONFIRMED`다.
4. **지운 코드의 마지막 모습은 [`d7b7cfd`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/d7b7cfdffaef79fe5392c0f267dc6fdf74e193f5)에 있다.** 확정의 조율은 [`OrderModifyService`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/d7b7cfdffaef79fe5392c0f267dc6fdf74e193f5/apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderModifyService.kt), 차감은 [`PointModifyService`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/d7b7cfdffaef79fe5392c0f267dc6fdf74e193f5/apps/commerce-api/src/main/kotlin/com/loopers/application/point/PointModifyService.kt)와 [`PointAccount`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/d7b7cfdffaef79fe5392c0f267dc6fdf74e193f5/apps/commerce-api/src/main/kotlin/com/loopers/domain/point/PointAccount.kt), 경쟁 장면은 [`OrderConfirmerConcurrencyTest`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/d7b7cfdffaef79fe5392c0f267dc6fdf74e193f5/apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderConfirmerConcurrencyTest.kt)에 있다.

## 고르지 않은 것

- **결제수단으로 고르게 둔다.** 포인트가 PG 곁의 두 번째 결제수단이 된다. 그러나 과제는 대신 결제를 금하고, 고르는 규칙과 API는 과제 밖이며, 확정의 장면 테스트가 두 길로 늘어난다.
- **아무도 부르지 않는 채로 두거나 테스트를 끈다.** 돈을 옮기는 코드가 쓰이지 않은 채 남아, 바꿀 때마다 함께 고쳐야 한다. 꺼진 테스트는 그 코드가 맞는지 말하지 않는다.

## 대가

- 포인트 결제를 다시 들이면 확정의 조율과 잠금 차례를 다시 짜야 한다. 확정이 TX A, PG 호출, TX B로 나뉘었으므로([ADR 0024](./0024-confirm-calls-the-pg-between-two-transactions.md)) 지운 코드는 출발점일 뿐 그대로 붙지 않는다.
- 이전 주문의 결제 기록은 확정된 주문의 결제액과 확정 시각뿐이다([ADR 0006](./0006-no-point-history-until-a-reader.md)).

## 다시 볼 조건

- 포인트가 PG 곁의 결제수단으로 돌아오면, 결제수단을 확정 요청에서 고르고 포인트 차감을 TX A의 참여자로 둔다.

## 바꾸는 결정

[ADR 0003](./0003-confirm-order-in-one-transaction.md)의 포인트 차감과 ADR 0019의 확정 경로(주문 → 상품 → 포인트 계정)가 사라진다. 날짜 메모는 구현하는 작업에서 단다. `CONTEXT.md`의 포인트와 주문을 확정하다가 이 ADR을 따른다.
