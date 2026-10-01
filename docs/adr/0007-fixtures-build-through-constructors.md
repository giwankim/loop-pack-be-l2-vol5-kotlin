---
status: accepted
date: 2026-09-30
---

# 테스트 fixture는 생성자로 만들고 Instancio는 값만 뽑는다

테스트 데이터를 손으로 적은 값 대신 fixture로 준비한다. 지금은 테스트 클래스마다 private 도우미가 따로 있다. `registerProduct`만 8곳에 다섯 가지 모양으로 있고 기본 가격과 재고도 제각각이다. 처음에는 Instancio(`instancio-junit` 6.1.0, 이미 모든 모듈의 테스트 경로에 있다)의 Bean Validation 연동으로 도메인 객체를 만들 생각이었다. 그러나 도메인에는 제약 애노테이션이 없고 규칙은 생성자에 있다([카탈로그 설계 5.18](../design/catalog.md)). Instancio가 엔티티를 만들면 그 생성자를 거치지 않는다. 그래서 fixture는 진짜 생성자를 부르고, Instancio는 엔티티의 상수가 정한 범위에서 값을 뽑는 데만 쓴다.

- 애그리거트마다 `<Aggregate>Fixtures.kt` 하나를 그 애그리거트의 domain 테스트 패키지에 둔다(예: `src/test/.../domain/product/ProductFixtures.kt`). 한 파일에 세 가지가 함께 있다.
  - 규칙마다 하나인 값 생성기(`productName()`, `productPrice()` …). 범위는 엔티티 상수(`Product.NAME_MAX_LENGTH`, `MIN_PRICE_AMOUNT`, `MAX_PRICE_AMOUNT`)에서 읽는다. 가격과 포인트 금액은 범위가 달라 공용 `createMoney()`는 두지 않는다.
  - 저장하지 않은 엔티티를 만드는 fixture(`createProduct(brand, …)`). domain 단위 테스트와 `@DataJpaTest`가 쓴다. Service·MockMvc 테스트도 다른 조각의 데이터를 그 조각의 저장소로 준비할 때 쓴다. 그래서 그 조각의 등록 규칙이 바뀌어도 상관없는 테스트가 깨지지 않는다.
  - 유효한 Request를 만드는 fixture(`createProductAdminRegisterRequest(brandId, …)`). Service·MockMvc 테스트가 자기 조각의 Service를 불러 데이터를 준비할 때 쓴다.
- 모든 필드는 기본값을 생성하는 이름 있는 파라미터라 테스트가 덮어쓸 수 있다. 기본값은 동작을 바꾸는 경계를 피한다.
  - 이름과 가격은 엔티티 상수가 허용하는 전체 범위다.
  - 재고는 100..1,000이라 0(품절)이 나오지 않는다. 주문 수량은 1..10이고 한 주문의 상품은 서로 다르다.
  - 충전액은 1..1,000,000이라 기본값끼리 더해도 잔액이 넘치지 않는다.
- 테스트는 단언하는 값과 기대는 경계를 직접 넘긴다. 그 밖의 인자는 검증하는 동작의 입력이어도 fixture가 채운다. MockMvc의 JSON 본문은 예외다. 필드 이름과 JSON 타입까지 HTTP 계약이라 손으로 적은 그대로 둔다.
- 값은 실행마다 바뀐다. `InstancioExtension`을 JUnit 확장 자동 탐지로 전역에 등록한다. 실패한 테스트의 seed는 그 테스트의 JUnit 보고 항목(`Instancio` 키)으로 남는다. 실행이 끝나면 실패한 테스트마다 붙일 `@Seed(…)` 줄을 모은 WARN 로그가 표준 출력에 찍힌다. `@Seed`로 그 실행을 되살린다.
- Instancio의 Bean Validation 연동은 commerce-api 테스트의 `instancio.properties`로 켠다. 연동은 `Instancio.create`·`Instancio.of`가 채우는 필드에만 걸리고, 생성기가 쓰는 `Instancio.gen()`은 애노테이션을 읽지 않는다. 그래서 연동이 바꾸는 fixture 값은 없고, 범위는 여전히 엔티티 상수를 읽는 생성기가 정한다.
- `UserFixture`는 `domain/user/UserFixtures.kt`로 옮긴다. 사용자와 0원 계정을 함께 저장하는 fixture이며 무작위 값은 없다([포인트·주문 설계 12.5](../design/points-orders.md)).
- 기존 테스트 전체를 옮긴다. 준비하는 방법이 두 가지로 남지 않게 한다. 코드 리뷰가 지킬 규칙은 `CODING_STANDARDS.md`에 적는다.

## 고르지 않은 것

- **Instancio로 엔티티를 바로 만든다.** 시험해 보니 kotlin-jpa 플러그인이 만든 인자 없는 생성자를 쓰고 필드를 리플렉션으로 채운다. 이름 검사와 `init` 블록이 하나도 돌지 않아 이름이 `"   "`이고 가격이 `Money(-5)`인 상품이 만들어진다. `BaseEntity`의 `id`와 `deletedAt`도 무작위라 200개가 모두 삭제된 상품으로 보였다. `@SQLRestriction`이 이런 행을 가리고, `id`가 0이 아니면 `save`는 삽입하지 않고 병합한다.
- **엔티티에 Bean Validation 애노테이션을 붙인다.** 같은 규칙이 세 번째 자리에 적히고, 테스트를 위해 운영 코드가 바뀐다. 위의 생성자 우회는 그대로 남는다.
- **Request fixture를 Bean Validation 연동과 `Instancio.of`로 만든다.** 연동을 켜도 꺼도 네 Request를 1,000개씩 만든 결과 위반은 없었다. Instancio 기본값(문자열 3..10자, 수 1..10,000)이 이미 모든 제약 안에 있다. 연동이 바꾸는 것은 값의 분포뿐이고, 바뀌는 쪽이 쓸모 있지도 않다. `@Size(max = 100)`이어도 이름은 10자를 넘지 않는다. 상한이 없는 `@Min(1)`과 `@Positive`는 `Long`·`Int`의 최댓값까지 가서, 기본값으로 두 번 충전하면 잔액 넘침으로 거절된다([포인트·주문 설계 5.7](../design/points-orders.md)). 재고의 `@Min(0)`은 품절도 허용하므로 재고는 어차피 덮어써야 한다. 엔티티 상수를 읽는 생성기는 같은 규칙을 한 곳에서 읽는다.
- **Kotlin `Random`으로 값을 뽑는다.** 실패한 seed를 보고하고 되살리는 일을 직접 만들어야 한다. 확장이 이미 하는 일이다.

## 대가

- `Instancio.gen()`은 6.1.0에서도 `@ExperimentalApi`다. API가 바뀌면 fixture 파일의 생성기만 고친다. 테스트는 Instancio를 직접 부르지 않는다.
- domain 테스트 패키지의 fixture 파일이 application의 Request를 import한다. `LayeredArchitectureTest`는 `ImportOption.DoNotIncludeTests`로 테스트를 보지 않으므로 규칙 위반이 되지 않는다. 테스트 코드를 ArchUnit 검사에 넣으려면 이 결정부터 다시 본다.
- 실패한 seed는 Gradle 콘솔에 나오지 않는다. 명령줄에서 돌렸다면 HTML 보고서에서 찾는다. 실패한 테스트 페이지의 data 탭에 seed가, 첫 페이지의 표준 출력 탭에 `@Seed(…)` 요약이 있다.
- 기본값이 경계를 피하므로 무작위 값이 드러내는 것은 단언하는 값을 넘기지 않은 테스트다. 경계에서만 드러나는 결함은 경계를 직접 넘기는 테스트가 지킨다.

## 다시 보는 조건

Request에 기본값보다 좁은 제약(`@Pattern`, `@Email`, 긴 최소 길이)이 생기면 그 Request의 fixture를 `Instancio.of`로 만드는 것을 다시 본다. 연동은 이미 켜져 있어 설정은 더하지 않아도 된다. commerce-batch나 commerce-streamer가 commerce-api의 fixture를 써야 하면 `java-test-fixtures` 소스 세트로 옮긴다.
