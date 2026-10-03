# Coding Standards

이 저장소의 코드가 지키는 규칙이다. `/code-review`는 변경을 이 문서에 비추어 본다. 결정의 까닭은 각 규칙이 가리키는 ADR과 설계 문서에 있다.

## 테스트

### 이름과 구조

- 테스트 메서드 이름은 백틱으로 감싼 영어 문장이다. 예: `` fun `registering a blank name returns 400 and saves nothing`() ``.
- 메서드 이름이 곧 표시 이름이라 `@DisplayName`을 붙이지 않는다.
- 테스트 클래스는 `@Nested` 없이 평평하다.
- `assertThrows`가 돌려준 예외는 `exception`이라는 변수에 담는다.

### Fixture

테스트 데이터는 fixture로 준비한다. 까닭과 고르지 않은 대안은 [ADR 0010](docs/adr/0010-entity-fixtures-built-by-instancio.md)에 있다. 파일 위치, seed 보고, Bean Validation 연동 설정은 [ADR 0007](docs/adr/0007-fixtures-build-through-constructors.md)에서 이어진다.

**테스트 쪽**

- 테스트는 단언하는 값, 기대는 경계(품절, 잔액 0원, 이름 길이의 한계 등), 기대는 값 사이의 관계(앞뒤 공백만 다른 이름, 대소문자만 다른 이름 등)를 이름 있는 인자로 넘긴다. 그 밖의 인자는 검증하는 동작의 입력이어도 fixture의 기본값에 맡긴다. 그래서 JSON 본문 밖에 남은 리터럴은 모두 그 테스트가 확인하거나 기대는 값이다.
- 생성된 값을 확인할 때는 fixture가 만든 객체에서 읽는다. 예: 요청 fixture의 `name`과 응답의 `name`을 비교한다.
- MockMvc 요청의 JSON 본문은 손으로 적는다. 필드 이름과 JSON 타입까지 HTTP 계약이다.
- Service·MockMvc 테스트는 자기 조각의 데이터를 그 Service와 Request fixture로 준비하고, 다른 조각의 데이터(브랜드 테스트의 상품 등)는 그 조각의 저장소에 엔티티 fixture를 저장해 준비한다. 다른 조각의 등록 규칙이 바뀌어도 테스트가 흔들리지 않는다.
- 테스트는 Instancio 대신 fixture를 부른다. 테스트에 들어가는 Instancio는 실패한 실행을 되살릴 때 잠시 붙이는 `@Seed`뿐이다.

**fixture 쪽**

- 애그리거트마다 `<Aggregate>Fixtures.kt` 하나를 그 애그리거트의 domain 테스트 패키지에 둔다. 엔티티 fixture와 Request fixture가 그 파일에 함께 있다. 엔티티 fixture가 함께 쓰는 도우미 `unsaved()`는 `com.loopers.domain`의 `EntityFixtures.kt`에 있다.
- fixture는 최상위 함수다. 저장까지 맡아 저장소를 주입받는 `UserFixture`만 Spring 컴포넌트다. 다른 애그리거트를 가리키는 인자(브랜드, 브랜드 ID, 사용자 ID, 상품)는 기본값 없이 받는다. 상품 없이 만드는 주문 품목만 상품 ID를 뽑는다. 상품이 필요 없는 Order 규칙 테스트가 쓰고, 상품 외래 키에 걸려 저장하지 않는다.
- 그 밖의 파라미터는 모두 nullable이고 기본값이 `null`이다. `null`이면 fixture가 값을 뽑으므로, 감싸는 도우미가 "지정하지 않음"을 그대로 넘길 수 있다. 원래 nullable인 필드(목록 필터의 `brandId`·`userId` 등)의 파라미터만은 `null`이 곧 값이다. 넘긴 인자는 반드시 쓴다.
- 엔티티·Request fixture의 이름은 `create`에 타입 이름을 잇는다(`createBrand`, `createProductAdminRegisterRequest`).
- 생성자가 입력을 검사하기만 하는 엔티티(`Brand`, `Product`, `Like`)와 모든 Request는 `KInstancio.of`로 만든다. 생성자가 필드를 계산하는 엔티티(`Order`, `PointAccount`)는 진짜 생성자를 부른다. 새 엔티티도 이 기준으로 고른다. 엔티티 fixture는 저장하지 않은 엔티티를 돌려주고, 저장은 테스트가 한다.
- Instancio로 만드는 엔티티 fixture는 `unsaved()`를 거쳐 `BaseEntity`의 `id`를 0, `deletedAt`을 `null`로 둔다. `id`가 0이 아니면 `save`가 삽입하지 않고 병합하고, `deletedAt`이 있으면 `@SQLRestriction`이 행을 가린다. 연관은 fixture가 직접 넣는다. `Product`의 `brand`를 맡기면 Instancio가 저장되지 않은 브랜드를 지어낸다.
- `create<타입>` 이름은 저장하지 않는 fixture만 쓴다. 저장까지 하는 테스트 도우미는 다른 이름으로 한 일을 드러낸다(사용자와 계정을 저장하는 `UserFixture.registerUser`). 용어집에서 주문의 행위는 `create`라 `createOrder`를 도우미 이름으로 쓰면 fixture와 겹친다.
- 규칙이 걸린 필드는 fixture가 그 자리에서 `gen()`으로 범위를 정해 `set`한다(이름 2..100자, 가격 1..1,000,000,000원). 따로 두는 값 생성기 함수는 없다. 값 객체(`Money`, `Stock`)는 Instancio에 맡기지 않고 뽑은 값으로 fixture가 만든다. 맡기면 값 객체의 검사도 건너뛰어 음수 금액이나 재고가 나온다. 기본값은 동작을 바꾸는 경계를 피한다(재고는 100..1,000개라 품절이 나오지 않는다).
- Request의 상한 없는 `@Positive`·`@Min` 필드도 fixture가 범위를 정한다. `instancio.properties`로 켠 Bean Validation 연동은 fixture가 빠뜨린 제약 필드를 채우는데, 상한 없는 필드에는 큰 값이 나와 기본값끼리 더하면 넘칠 수 있다.
- fixture 파일마다 같은 패키지에 계약 테스트가 있다. Instancio가 생성자를 건너뛰므로 범위는 여기서 지킨다. 기본값을 1,000개 뽑아 엔티티 fixture의 값은 진짜 생성자에 다시 넣어 예외가 없는지와 저장하지 않은 상태(`id` 0, `deletedAt` `null`)를 확인하고, Request fixture의 값은 그 Request의 제약으로 검증한다. 기본값이 피하는 경계(품절 등)도 확인한다. fixture마다 인자를 넘겨 그 값이 그대로 쓰이는지 하나씩 확인한다. fixture를 더하거나 범위를 바꾸면 계약 테스트도 함께 바꾼다. 계약 테스트가 없는 fixture를 두지 않는다.
