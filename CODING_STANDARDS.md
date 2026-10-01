# Coding Standards

이 저장소의 코드가 지키는 규칙이다. `/code-review`는 변경을 이 문서에 비추어 본다. 결정의 까닭은 각 규칙이 가리키는 ADR과 설계 문서에 있다.

## 테스트

### 이름과 구조

- 테스트 메서드 이름은 백틱으로 감싼 영어 문장이다. 예: `` fun `registering a blank name returns 400 and saves nothing`() ``.
- 메서드 이름이 곧 표시 이름이라 `@DisplayName`을 붙이지 않는다.
- 테스트 클래스는 `@Nested` 없이 평평하다.
- `assertThrows`가 돌려준 예외는 `exception`이라는 변수에 담는다.

### Fixture

테스트 데이터는 fixture로 준비한다. 까닭과 고르지 않은 대안은 [ADR 0007](docs/adr/0007-fixtures-build-through-constructors.md)에 있다.

**테스트 쪽**

- 테스트는 단언하는 값, 기대는 경계(품절, 잔액 0원, 이름 길이의 한계 등), 기대는 값 사이의 관계(앞뒤 공백만 다른 이름, 대소문자만 다른 이름 등)를 이름 있는 인자로 넘긴다. 그 밖의 인자는 검증하는 동작의 입력이어도 fixture의 기본값에 맡긴다. 그래서 JSON 본문 밖에 남은 리터럴은 모두 그 테스트가 확인하거나 기대는 값이다.
- 생성된 값을 확인할 때는 fixture가 만든 객체에서 읽는다. 예: 요청 fixture의 `name`과 응답의 `name`을 비교한다.
- MockMvc 요청의 JSON 본문은 손으로 적는다. 필드 이름과 JSON 타입까지 HTTP 계약이다.
- Service·MockMvc 테스트는 자기 조각의 데이터를 그 Service와 Request fixture로 준비하고, 다른 조각의 데이터(브랜드 테스트의 상품 등)는 그 조각의 저장소에 엔티티 fixture를 저장해 준비한다. 다른 조각의 등록 규칙이 바뀌어도 테스트가 흔들리지 않는다.
- 테스트는 Instancio 대신 fixture를 부른다. 테스트에 들어가는 Instancio는 실패한 실행을 되살릴 때 잠시 붙이는 `@Seed`뿐이다.
- 엔티티는 fixture든 테스트든 진짜 생성자로 만든다. `Instancio.create`·`Instancio.of`는 생성자 검사를 건너뛴다.

**fixture 쪽**

- 애그리거트마다 `<Aggregate>Fixtures.kt` 하나를 그 애그리거트의 domain 테스트 패키지에 둔다. 값 생성기, 엔티티 fixture, Request fixture가 그 파일에 함께 있다.
- fixture와 생성기는 최상위 함수다. 저장까지 맡아 저장소를 주입받는 `UserFixture`만 Spring 컴포넌트다. 모든 필드는 생성된 기본값을 가진 이름 있는 파라미터다. 다른 애그리거트를 가리키는 인자(브랜드, 브랜드 ID, 사용자 ID, 상품)만 기본값 없이 받는다.
- 엔티티·Request fixture의 이름은 `a`/`an`에 타입 이름을 잇는다(`aBrand`, `aProductAdminRegisterRequest`). 생성기의 이름은 규칙을 말한다(`brandName`, `productPrice`).
- 엔티티 fixture는 진짜 생성자를 불러 저장하지 않은 엔티티를 돌려준다. 저장은 테스트가 한다.
- 생성기는 `Instancio.gen()`으로 엔티티 상수가 정한 범위에서 값을 뽑는다. 기본값은 동작을 바꾸는 경계를 피한다(재고는 0이 되지 않는다). Instancio의 Bean Validation 연동은 `instancio.properties`로 켜 두었지만 `Instancio.gen()`은 애노테이션을 읽지 않는다. 범위는 연동이 아니라 생성기가 정한다.
- 생성기가 있는 fixture 파일마다 같은 패키지에 계약 테스트가 있다. 기본값을 1,000개쯤 뽑아 문서화한 범위를 확인하고, 모든 Request fixture를 그 Request의 제약으로 검증한다. 생성기나 fixture를 더하거나 범위를 바꾸면 계약 테스트도 함께 바꾼다.
