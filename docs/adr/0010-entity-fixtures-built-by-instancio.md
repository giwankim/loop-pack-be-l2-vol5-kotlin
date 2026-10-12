---
status: accepted
date: 2026-10-03
---

# fixture는 Instancio가 만들고, 필드를 계산하는 엔티티만 생성자를 부른다

[ADR 0007](./0007-fixtures-build-through-constructors.md)은 fixture가 진짜 생성자를 부르고 Instancio는 값만 뽑게 했다. 아키텍처 리팩터링 중 코드를 줄이면서 이것을 뒤집는다. 엔티티와 Request의 fixture를 `KInstancio.of`로 만든다. 새 필드가 생겨도 Instancio가 채우므로 생성자 시그니처를 fixture가 따라가지 않는다. 대신 생성자의 검사가 돌지 않으므로 fixture 값이 규칙 안에 있는지는 fixture가 정한 범위와 fixture 계약 테스트가 지킨다.

- 생성자가 입력을 검사하기만 하는 엔티티(`Brand`, `Product`, `Like`)와 모든 Request는 Instancio로 만든다. 생성자가 필드를 계산하는 엔티티는 생성자를 부른다: `Order`(품목 정렬, `totalAmount` 합산, `DRAFT` 시작), `PointAccount`(잔액 0). 새 엔티티도 이 기준으로 고른다.
- `BaseEntity`의 `id`와 `deletedAt`은 공유 도우미 하나가 모든 엔티티 fixture에서 비운다. `id`가 0이 아니면 `save`가 삽입하지 않고 병합하고, `deletedAt`이 있으면 `@SQLRestriction`이 행을 가린다. `createdAt`·`updatedAt`은 `@PrePersist`가 덮어쓴다.
- 연관은 fixture가 직접 넣는다. `Product`의 `brand`를 Instancio에 맡기면 저장되지 않은 브랜드가 생겨 상품 저장이 실패한다.
- 규칙이 걸린 필드는 fixture가 그 자리에서 `gen()` 명세로 범위를 정해 `set`한다(예: 이름 2..100자). 따로 두던 값 생성기 함수(`brandName()` 등)는 없앤다. Request의 상한 없는 `@Positive`·`@Min` 필드도 fixture가 범위를 정한다. Bean Validation 연동에 맡기면 큰 값이 나와 기본값끼리 충전해도 잔액이 넘친다(ADR 0007).
- fixture 계약 테스트가 범위를 지킨다. 엔티티 fixture의 값은 진짜 생성자에 다시 넣어 보고, Request fixture의 값은 그 Request의 제약으로 검증한다. 규칙이 좁아지면 테스트가 무효한 값으로 조용히 도는 대신 여기서 깨진다.
- fixture 파라미터는 nullable이고 `null`이면 fixture가 값을 뽑는다. 감싸는 도우미가 "지정하지 않음"을 그대로 넘길 수 있다. 원래 nullable인 필드(목록 필터 `brandId`·`userId` 등)의 파라미터는 `null`이 곧 값이며 기본값도 `null`이다. 넘긴 인자는 반드시 쓴다.
- 파일 위치, seed 보고, Bean Validation 연동 설정, 기존 테스트 전체 이전은 ADR 0007 그대로다.

## 고르지 않은 것

- **ADR 0007을 유지한다.** 생성자가 모든 fixture 값을 검사해 주지만, fixture가 생성자 시그니처마다 따라 바뀐다.
- **Request는 생성자로 만든다.** 데이터 클래스라 건너뛸 검사가 없어 가장 단순하지만, Request에 필드가 늘 때마다 fixture가 따라 바뀐다.
- **모든 엔티티를 Instancio로 만든다.** `Order` fixture가 정렬·합산·상태를 다시 구현하게 되어 생성자의 사본이 된다. 무작위 `status`·`paidAmount`·`confirmedAt` 조합은 `Order`의 CHECK 제약에도 걸린다.

## 대가

- 엔티티를 만드는 길이 두 가지다. 어느 쪽인지는 위 기준이 정한다.
- 범위가 엔티티 상수가 아니라 fixture에 적힌다. 규칙과 범위가 어긋나면 생성자나 Request 제약이 아니라 계약 테스트가 잡는다. 계약 테스트가 없는 fixture를 두지 않는다.
- Request fixture가 제약 있는 필드를 빠뜨리면 Bean Validation 연동이 값을 채운다. 제약 안의 값이지만 상한 없는 필드에서는 쓰기에 따라 넘침을 부를 수 있다.
- ADR 0007을 전제로 쓴 fixture 이전 이슈(#34, #36–#40)와 `CODING_STANDARDS.md`의 fixture 규칙을 이 결정에 맞춰 고친다.
