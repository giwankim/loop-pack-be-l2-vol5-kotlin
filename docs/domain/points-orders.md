# 포인트·주문 도메인 — 규칙, 속성, 행위

개념의 뜻은 [`CONTEXT.md`](../../CONTEXT.md)에 있고 여기서는 반복하지 않는다. 표기와 예외 규칙은 [카탈로그 도메인](./catalog.md)과 같다. 구조와 API, 결정은 [`docs/design/points-orders.md`](../design/points-orders.md)에 있다.

2026-09-18 기준 충전과 잔액 조회(#12)까지 구현되었다. 주문·결제 이력은 #13–#16에서 더한다.

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

- 사용자마다 계정 하나. 사용자 fixture와 함께 만들고, 조회나 충전이 없는 계정을 만들어 주지 않는다. 사용자는 있는데 계정이 없으면 application이 `POINT_ACCOUNT_MISSING`(500)이다(설계 5.9, 12.5).
- 잔액은 0 이상이다. `Money`가 지킨다. 상품 가격의 상한은 잔액에 적용되지 않고 `Long` 범위만 지킨다(설계 5.7).
- 충전액은 1원 이상이다. 어기면 `InvalidChargeAmountException`. 충전 후 잔액이 `Long` 범위를 넘으면 `InvalidMoneyException`. 어느 쪽이든 잔액은 그대로다.

### 행위

| 메서드 | 하는 일 | 거절 |
| --- | --- | --- |
| `PointAccount(user)` | 그 사용자의 0원 계정을 만든다 | 없음 |
| `charge(amount, chargeKey)` | 잔액에 `amount`를 더하고 그 충전의 CHARGE `PointHistory`를 돌려준다. 저장은 부르는 쪽이 한다 | `InvalidChargeAmountException`, `InvalidMoneyException` |

### 저장 약속

`PointAccountRepository`: `save`, `findByUserId`. 없는 계정은 null이다.

### 협력

- 충전: `PointService.charge` → 요청자 존재 확인 → 계정 조회 → 같은 충전 키의 성공 이력 조회 → 있으면 충전액을 견줘 첫 결과 재생(같음) 또는 `IDEMPOTENCY_KEY_CONFLICT`(다름) → 없으면 `account.charge(amount, chargeKey)` → 돌려받은 이력 저장. 잔액 변경과 이력 저장은 한 트랜잭션이다(설계 5.5).
- 잔액 조회: `PointService.findBalance` → 요청자 존재 확인 → 계정 조회 → 현재 잔액.

## 포인트 이력 (PointHistory)

`BaseEntity`를 상속한다. 남긴 뒤 바꾸지 않는 기록이며 별도 애그리거트가 아니다. `PointAccount.charge`만 만든다(설계 12.6).

### 속성

| 이름 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `Long` | 식별자 |
| `account` | `PointAccount` | 잔액이 바뀐 계정. `@ManyToOne(fetch = LAZY)`, 읽기용. DB 외래 키의 자리 |
| `type` | `PointHistoryType` | 성공의 종류. 지금은 `CHARGE`뿐 |
| `amount` | `Money` | 잔액을 바꾼 금액. 양수 |
| `balanceAfter` | `Money` | 이 기록 직후의 잔액. 뒤에 잔액이 바뀌어도 그대로다 |
| `chargeKey` | `String` | 충전 키. 계정 안에서 유일, 대소문자 구분 |

테이블 `point_history`. `(point_account_id, charge_key)` 유일(`uk_point_history_point_account_id_charge_key`), `point_account`로 외래 키(`fk_point_history_point_account`). `charge_key`는 `varchar(128) character set utf8mb4 collate utf8mb4_bin`이라 조회와 유일 제약이 대소문자를 구분한다(설계 12.2).

### 규칙

- 성공해서 잔액이 바뀐 기록만 남긴다. 실패한 시도와 성공의 재생은 기록을 늘리지 않는다(설계 5.4).
- 같은 계정에 같은 충전 키의 기록은 하나다. `Charge-A`와 `charge-a`는 다른 키다.
- 이력의 `balanceAfter`는 그 충전 직후의 계정 잔액과 같다. 계정이 이력을 만들며 지킨다.

### 저장 약속

`PointHistoryRepository`: `save`, `findByAccountIdAndChargeKey`. 이력을 바꾸거나 지우는 약속은 없다.

## 충전 키 (chargeKey)

값이며 따로 타입을 두지 않는다. 허용 형식은 `PointChargeRequest.CHARGE_KEY_PATTERN` = `[A-Za-z0-9_-]{1,128}` 하나다.

- HTTP `Idempotency-Key` 헤더가 없거나 형식을 어기면 interfaces(`IdempotencyKeyHeader.require`)가 `INVALID_IDEMPOTENCY_KEY`(400)로 거절한다. 같은 형식을 `PointChargeRequest`의 `@Pattern`이 Service 입구에서 한 번 더 본다(설계 12.4).
- 공백을 떼거나 대소문자를 바꾸지 않는다.
- 범위는 사용자 + 작업 종류다. 다른 사용자의 같은 키, 같은 사용자의 충전과 주문 생성에 쓴 같은 키는 서로 무관하다. 충전의 키는 `point_history`에, 생성의 키는 주문에 남으므로 저장 위치가 다르다(ADR 0004).

## 사용자 (User)와 요청자

카탈로그 도메인의 규칙이 그대로다. 포인트 충전·잔액 조회도 요청자가 있어야 하며, 헤더의 존재는 interfaces(`UserIdHeader`)가, 사용자의 존재는 application(`PointService`)이 본다. 요청자는 자기 잔액만 다룬다. 서비스가 받는 사용자 식별자는 요청자 하나뿐이라 남의 잔액을 부를 길이 없다.
