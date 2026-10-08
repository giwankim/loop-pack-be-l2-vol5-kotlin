# Coding Standards

이 저장소의 코드가 지키는 규칙이다. `/code-review`는 변경을 이 문서에 비추어 본다. 결정의 까닭은 각 규칙이 가리키는 ADR과 설계 문서에 있다.

## 테스트

### 이름과 구조

- 테스트 메서드 이름은 백틱으로 감싼 영어 문장이다. 예: `` fun `registering a blank name returns 400 and saves nothing`() ``.
- 메서드 이름이 곧 표시 이름이라 `@DisplayName`을 붙이지 않는다.
- 테스트 클래스는 `@Nested` 없이 평평하다.
- `assertThrows`가 돌려준 예외는 `exception`이라는 변수에 담는다.

### 단언

- 단언은 차례로 적는다. `assertAll`이나 soft assertion(`assertSoftly`, `SoftAssertions`)으로 묶지 않는다. 테스트는 동작 하나를 보므로 첫 실패에서 멈추면 충분하다.

### 쿼리 수

- 쿼리 수를 세는 테스트는 jpa `testFixtures`의 `withStatistics`로 Hibernate 통계를 켠다. 검증하는 동작과 그 단언을 블록 안에 적는다.

  ```kotlin
  entityManager.withStatistics { statistics ->
      val slice = productFinder.findAll(ProductListRequest())

      assertThat(slice.content).hasSize(3)
      assertThat(statistics.prepareStatementCount).isEqualTo(2L)
  }
  ```
- 컨텍스트 속성(`hibernate.generate_statistics`)으로 켜지 않는다. 속성이 다르면 컨텍스트 캐시 키가 갈려 Spring 컨텍스트가 하나 더 뜬다.
- 통계를 손으로 켜고 try/finally로 끄지 않는다. 켠 통계는 컨텍스트를 나눠 쓰는 다음 테스트까지 남으므로, 끄는 일은 도우미가 맡는다.

### HTTP 테스트

HTTP 테스트는 `MockMvcTester`로 요청하고 단언한다. 까닭과 고르지 않은 대안은 [ADR 0011](docs/adr/0011-http-tests-assert-through-mockmvctester.md)에 있다.

- `MockMvcTester`는 `BaseWebApiAdapterTest`의 `mvc` 필드로 쓴다.
- HTTP 테스트의 클래스 이름은 시험하는 web API 어댑터 클래스의 이름에 `Test`를 잇는다(`BrandApi` → `BrandApiTest`). 어댑터 하나의 일부 동작만 떼어 낸 테스트는 그 동작을 이름으로 쓴다(`OrderApi`의 확정을 보는 `OrderConfirmationApiTest`, 본문 오류 응답을 보는 `RequestBodyErrorApiTest`). 테스트가 어떻게 도는지는 상속한 기반 클래스가 말하므로 이름에 `MockMvc`를 넣지 않는다. 실제 서버로 요청하는 테스트가 생기면 이름에 구분을 단다(`BrandApiE2ETest`).
- 상태는 `hasStatusOk()`나 `hasStatus(HttpStatus.…)`로 단언한다. 본문을 단언할 때는 그 결과에서 `bodyJson()`을 받아 지역 변수에 둔다. 상태도 보면 같은 줄에서 먼저 단언한다(`assertThat(result).hasStatusOk().bodyJson()`).
- 본문 지역 변수의 이름은 `body`다. 한 테스트에서 본문을 둘 이상 받으면 첫째가 `body`이고, 뒤의 것은 읽은 것을 따라 짓는다. 상세는 `detail`, 목록은 `list`, 오류 응답은 `error`다. 오류 응답은 쓰기 요청의 것이어도 `error`이고, 오류 응답을 또 받으면 몇 번째인지 앞에 붙인다(`secondError`). 뒤에 받는 쓰기 요청의 응답은 그 요청 이름에 `Body`를 붙인다(`unlikeBody`). 그 이름을 이미 썼으면 그 요청이 몇 번째인지 앞에 붙인다(`secondChargeBody`). 이미 쓴 이름을 다시 읽으면 무엇 뒤에 읽었는지 붙인다(`detailAfterUnlike`). 반복문 안에서 받은 본문도 그 테스트의 본문으로 센다. 반복문이 `body`를 썼으면 뒤에서는 `body`를 다시 쓰지 않는다. 단언 도우미(`assertAlreadyConfirmed`, `balance` 등)와 테스트 안의 지역 함수가 받는 본문은 그 함수 안에서 센다. 함수의 첫 본문은 `body`이고, 부르는 테스트의 본문 이름과 겹쳐도 된다.
- 요청에 실을 JSON을 담는 파라미터와 변수의 이름은 `json`이다. 응답 본문 `body`와 겹치지 않게 한다. 요청 JSON을 만드는 도우미 함수도 `body`라 부르지 않고 무엇을 싣는지 따라 짓는다(`item`, `items`).
- 본문은 JSON 경로 하나에 한 줄씩 단언한다.
  - 값은 `extractingPath(…).isEqualTo(…)`
  - 없어야 하는 필드는 `doesNotHavePath(…)`
  - 부분 문자열은 `extractingPath(…).asString().contains(…)`
  - 있기만 하면 되는 값은 `isNotNull()`, 수인지는 `asNumber()`
  - 빈 배열은 `extractingPath(…).asArray().isEmpty()`

  Hamcrest와 `ResultMatcher`(`andExpect`)는 쓰지 않는다.

  ```kotlin
  val body = assertThat(requestPostBrand(name = "루퍼스")).hasStatus(HttpStatus.CREATED).bodyJson()
  body.extractingPath("$.data.name").isEqualTo("루퍼스")
  body.extractingPath("$.data.createdAt").isNotNull()
  ```
- 기대값이 `Long`이면 `com.loopers.support`의 `isEqualToLong`으로 단언한다. JSONPath는 `Int` 범위의 정수를 `Integer`로 읽으므로 `isEqualTo(brand.id)`는 맞는 ID에서도 실패한다.
- 요청 빌더는 `assertThat`에 넘기거나 `exchange()`를 부를 때마다 실행된다.
  - 단언에 한 번 넘기는 요청은 빌더를 그대로 `assertThat`에 넘긴다.
  - 두 번 이상 단언하거나 단언하지 않는 요청은 `exchange()`로 한 번 실행하고 그 결과를 쓴다.
- 요청 도우미는 요청을 실행하고 `MvcTestResult`를 돌려준다. 이름은 `request`로 시작한다(`requestPostBrand`, `requestDeleteBrand`, `requestLike`). 기반 클래스의 준비·변경 도우미(`deleteBrand` 등)와 이름이 겹치지 않아, 준비와 검증하는 요청이 한눈에 갈린다. 결과는 여러 번 단언해도 요청을 다시 보내지 않는다. 관리자 요청 도우미는 요청마다 `with(…)`로 요청자를 걸고, 쓰기 요청이면 `with(csrf())`도 건다. 요청자를 바꿔 보는 테스트(403 등)가 있는 클래스의 도우미는 `principal: RequestPostProcessor? = ADMIN`을 받는다. `principal`이 `null`이면 식별 없는 요청이다.
- 응답의 ID를 다음 요청에 쓸 때는 그 경로의 `asNumber()` 단언에서 꺼낸다. `andReturn()`과 `JsonPath.read`를 거치지 않는다.

  ```kotlin
  val id = body.extractingPath("$.data.id").asNumber().actual().toLong()
  ```
- 두 응답을 `JsonNode`의 부분 트리로 견주는 테스트(주문의 목록 항목과 상세 등)는 클래스 안의 도우미 `MvcTestResult.json()`으로 본문을 읽는다. 같은 응답을 단언도 하고 견주기도 하면 그 결과를 지역 변수에 받는다. 이름은 응답이 무엇인지 따라 짓고(`created`, `ownDetail`, `missing`, `original`, `orders`) 본문 이름(`body`, `detail` 등)과 겹치지 않게 한다. 상태와 경로는 그 변수로 단언하고, 견줄 때 `json()`으로 읽는다. 주문 테스트에 이미 있던 `JsonNode` 필드 단언(목록 항목의 스냅샷, 쪽마다의 차례)은 ADR 0011대로 그대로 둔다. 한 응답의 필드를 새로 단언할 때는 `extractingPath`를 쓴다.

### 도우미

- 테스트 도우미는 `com.loopers.support`에 둔다. 여기서 도우미는 여러 테스트 클래스가 함께 쓰는 함수와 클래스다. 모든 모듈의 테스트와 `testFixtures`에 똑같이 적용한다(jpa `testFixtures`의 `DatabaseCleanUp`, redis `testFixtures`의 `RedisCleanUp` 등).
- 다음은 이 규칙에 들지 않는다.
  - fixture와 fixture 곁의 도우미(`unsaved()`). 아래 [Fixture](#fixture)를 따른다.
  - 한 테스트 클래스 안에서만 쓰는 도우미(MockMvc 테스트의 요청 도우미 등). 그 클래스에 둔다.
  - Testcontainers 설정(`com.loopers.testcontainers`).
- 테스트 기반 클래스는 `com.loopers.support.test`에 둔다. 까닭과 고르지 않은 대안은 [ADR 0012](docs/adr/0012-tests-inherit-setup-from-abstract-base-classes.md)에 있다. 서비스·MockMvc·저장소 테스트는 모두 자기 종류의 기반 클래스를 상속한다.
  - 서비스(provided 포트) 테스트: `BaseApplicationServiceTest`
  - MockMvc 테스트: `BaseWebApiAdapterTest`. `BaseApplicationServiceTest`를 넓혀 MockMvc와 관리자 보안 설정을 더한다.
  - 저장소(required 포트) 테스트: `BaseRepositoryTest`
  - domain 테스트와 컨텍스트 적재 테스트(`CommerceApiContextTest`)는 상속하지 않는다. 다만 저장한 엔티티가 있어야 하는 fixture 계약 테스트(`OrderFixturesTest`)는 domain 패키지에 있어도 `@DataJpaTest`이므로 `BaseRepositoryTest`를 상속한다.
- 테스트 설정 애노테이션(`@SpringBootTest`, `@DataJpaTest`, `@AutoConfigureMockMvc`, `@AutoConfigureTestDatabase`, `@Transactional`, 공통 `@Import`)은 기반 클래스만 진다. 하위 클래스가 더하는 애노테이션은 추가 `@Import`(QueryDSL 설정과 그 어댑터)와 트랜잭션에서 빠지는 표시뿐이다. 하위 클래스의 `@Import`는 기반의 것과 합쳐진다.
- `entityManager`와 `mvc`는 기반 클래스의 `protected` 필드로 쓴다. 준비만을 위해 포트나 저장소를 주입받지 않는다. 생성자로 받는 것은 검증하는 포트와 단언·정리에 쓰는 의존이다. 포트로도 저장소로도 만들 수 없는 상태(정해 둔 시각, 표현 범위를 넘는 가격 등)를 준비한 데이터 위에 SQL로 덮어쓰는 클래스는 `JdbcTemplate`도 받는다.
- 기반 클래스는 테스트마다 롤백되는 테스트 트랜잭션을 건다. 요청마다 커밋된 결과를 다음 요청이 읽어야 하는 클래스는 클래스에 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`를 달아 빠지고, 그 까닭을 클래스 KDoc에 적는다. 메서드마다 빠지지 않는다. 빠진 클래스는 `DatabaseCleanUp`으로 정리한다. 다시 읽은 엔티티가 분리되어 있으므로 지연 연관(`Product.brand` 등)을 건드리지 않는다.

### Fixture

테스트 데이터는 fixture로 준비한다. 까닭과 고르지 않은 대안은 [ADR 0010](docs/adr/0010-entity-fixtures-built-by-instancio.md)에 있다. 파일 위치, seed 보고, Bean Validation 연동 설정은 [ADR 0007](docs/adr/0007-fixtures-build-through-constructors.md)에서 이어진다. 준비한 데이터를 저장하는 일은 기반 클래스의 `prepare<타입>`이 맡는다([ADR 0012](docs/adr/0012-tests-inherit-setup-from-abstract-base-classes.md)).

**테스트 쪽**

- 테스트는 단언하는 값, 기대는 경계(품절, 잔액 0원, 이름 길이의 한계 등), 기대는 값 사이의 관계(앞뒤 공백만 다른 이름, 대소문자만 다른 이름 등)를 이름 있는 인자로 넘긴다. 그 밖의 인자는 검증하는 동작의 입력이어도 fixture의 기본값에 맡긴다. 그래서 JSON 본문 밖에 남은 리터럴은 모두 그 테스트가 확인하거나 기대는 값이다.
- 생성된 값을 확인할 때는 fixture나 `prepare`가 만든 객체에서 읽는다. 예: 요청 fixture의 `name`과 응답의 `name`을 비교한다.
- MockMvc 요청의 JSON 본문은 손으로 적는다. 필드 이름과 JSON 타입까지 HTTP 계약이다.
- 서비스·MockMvc·저장소 테스트는 자기 조각이든 다른 조각이든 데이터를 기반 클래스의 `prepare<타입>`과 변경 도우미로 준비한다. 준비하려고 포트를 직접 부르지 않는다. 포트를 직접 부르는 것은 검증하는 동작과 그 결과를 읽는 단언뿐이다. 한 클래스만 쓰는 준비 도우미(`BrandRepositoryTest`의 `prepareBrandRegisteredAt` 등)는 그 클래스에 private으로 둔다.
- `prepare`가 데이터를 만드는 길은 기반마다 다르다.
  - `BaseApplicationServiceTest`(`BaseWebApiAdapterTest`도)는 그 조각의 provided 포트가 만들 수 있는 상태를 포트와 Request fixture로 만든다(브랜드는 `BrandRegister`, 상품은 `ProductRegister`). 포트가 돌려준 엔티티는 그대로 돌려주고, `ProductInfo`를 돌려주는 상품만 엔티티를 ID로 다시 읽는다([ADR 0014](docs/adr/0014-finders-load-whole-aggregates.md)). 포트가 없는 데이터(사용자, 0원 포인트 계정)와 포트가 막는 상태는 저장소로 만든다. 저장할 엔티티는 fixture가 있으면 fixture로, 없으면 생성자로 만든다(아래 fixture 쪽). 포트가 막는 상태를 만드는 도우미는 우회를 이름에 드러낸다(`deleteBrandKeepingProducts`).
  - `BaseRepositoryTest`는 같은 이름으로 저장소에 엔티티를 저장한다. 저장소 테스트의 컨텍스트에는 포트가 없다.
- `prepare`가 다른 애그리거트를 받는 파라미터의 기본값은 `prepare`로 저장한 엔티티다(`prepareProduct(brand: Brand = prepareBrand())`). 그 밖의 파라미터는 nullable이고 기본값 `null`을 fixture에 그대로 넘긴다.
- `prepare`는 마지막으로 준비한 엔티티를 타입마다 `protected` 필드(`brand`, `product`)에 둔다. 다른 `prepare`의 기본값으로 불린 `prepare`도 필드를 바꾸며, 나중 것이 남는다. 테스트는 기본값으로 만든 것까지 세어 그 타입의 엔티티를 하나만 준비했을 때만 필드를 읽고, 그렇지 않으면 반환값을 쓴다.

  ```kotlin
  prepareProduct() // 브랜드도 기본값으로 하나 준비된다
  deleteProduct()

  brandRegister.delete(brand.id) // 브랜드도 상품도 하나뿐이라 필드를 읽는다
  ```
- 준비한 데이터의 상태를 바꿀 때는 `prepare`에 변경 도우미(`deleteBrand`, `deleteProduct` 등)를 잇는다. `prepareDeleted…` 같은 변형을 두지 않는다. 변경 도우미의 이름은 용어집의 행위를 쓰고, 대상의 기본값은 필드다. `BaseApplicationServiceTest`의 변경 도우미는 포트를 거친다. `BaseRepositoryTest`의 변경 도우미와 `BaseApplicationServiceTest`에서 포트가 막는 상태를 만드는 도우미는 바꾼 엔티티를 명시적으로 저장한다. 변경 감지는 테스트 트랜잭션이 있어야 DB에 닿으므로 기대지 않는다.
- `prepare`와 변경 도우미는 처음 쓰는 테스트가 생길 때 더한다. 쓰는 테스트가 없는 도우미를 미리 두지 않는다.
- 테스트는 Instancio 대신 fixture를 부른다. 테스트에 들어가는 Instancio는 실패한 실행을 되살릴 때 잠시 붙이는 `@Seed`뿐이다.

**fixture 쪽**

- 애그리거트마다 `<Aggregate>Fixtures.kt` 하나를 그 애그리거트의 domain 테스트 패키지에 둔다. 엔티티 fixture와 Request fixture가 그 파일에 함께 있다. 엔티티 fixture가 함께 쓰는 도우미 `unsaved()`는 `com.loopers.domain`의 `EntityFixtures.kt`에 있다.
- fixture는 최상위 함수다. 다른 애그리거트를 가리키는 인자(브랜드, 브랜드 ID, 사용자 ID, 상품)는 기본값 없이 받는다. 상품 없이 만드는 주문 품목만 상품 ID를 뽑는다. 상품이 필요 없는 Order 규칙 테스트가 쓰고, 상품 외래 키에 걸려 저장하지 않는다.
- 그 밖의 파라미터는 모두 nullable이고 기본값이 `null`이다. `null`이면 fixture가 값을 뽑으므로, 감싸는 도우미가 "지정하지 않음"을 그대로 넘길 수 있다. 원래 nullable인 필드(목록 필터의 `brandId`·`userId` 등)의 파라미터만은 `null`이 곧 값이다. 넘긴 인자는 반드시 쓴다.
- 엔티티·Request fixture의 이름은 `create`에 타입 이름을 잇는다(`createBrand`, `createProductAdminRegisterRequest`).
- 생성자가 입력을 검사하기만 하는 엔티티(`Brand`, `Product`)와 모든 Request는 `KInstancio.of`로 만든다. 생성자가 필드를 계산하는 엔티티(`Order`)는 진짜 생성자를 부른다. 새 엔티티도 이 기준으로 고른다(ADR 0010). 뽑을 값이 없는 엔티티(`User`, 다른 애그리거트의 ID만 받는 `Like`, 사용자 ID만 받는 `PointAccount`)는 fixture를 두지 않고 생성자를 부른다. 엔티티 fixture는 저장하지 않은 엔티티를 돌려주고, 저장은 기반 클래스의 `prepare<타입>`이 한다.
- Instancio로 만드는 엔티티 fixture는 `unsaved()`를 거쳐 `BaseEntity`의 `id`를 0, `deletedAt`을 `null`로 둔다. `id`가 0이 아니면 `save`가 삽입하지 않고 병합하고, `deletedAt`이 있으면 `@SQLRestriction`이 행을 가린다. 연관은 fixture가 직접 넣는다. `Product`의 `brand`를 맡기면 Instancio가 저장되지 않은 브랜드를 지어낸다.
- `create<타입>` 이름은 저장하지 않는 fixture만 쓴다. 저장까지 하는 도우미는 기반 클래스의 `prepare<타입>`이다(사용자와 0원 계정을 저장하는 `prepareUser`). 용어집에서 주문의 행위는 `create`라 `createOrder`를 도우미 이름으로 쓰면 fixture와 겹친다.
- 규칙이 걸린 필드는 fixture가 그 자리에서 `gen()`으로 범위를 정해 `set`한다(이름 2..100자, 가격 1..1,000,000,000원). 따로 두는 값 생성기 함수는 없다. 값 객체(`Money`, `Stock`)는 Instancio에 맡기지 않고 뽑은 값으로 fixture가 만든다. 맡기면 값 객체의 검사도 건너뛰어 음수 금액이나 재고가 나온다. 기본값은 동작을 바꾸는 경계를 피한다(재고는 100..1,000개라 품절이 나오지 않는다).
- Request의 상한 없는 `@Positive`·`@Min` 필드도 fixture가 범위를 정한다. `instancio.properties`로 켠 Bean Validation 연동은 fixture가 빠뜨린 제약 필드를 채우는데, 상한 없는 필드에는 큰 값이 나와 기본값끼리 더하면 넘칠 수 있다.
- 값을 뽑는 fixture 파일마다 같은 패키지에 계약 테스트가 있다. Instancio가 생성자를 건너뛰므로 범위는 여기서 지킨다. 기본값을 1,000개 뽑아 엔티티 fixture의 값은 진짜 생성자에 다시 넣어 예외가 없는지와 저장하지 않은 상태(`id` 0, `deletedAt` `null`)를 확인하고, Request fixture의 값은 그 Request의 제약으로 검증한다. 기본값이 피하는 경계(품절 등)도 확인한다. fixture마다 인자를 넘겨 그 값이 그대로 쓰이는지 하나씩 확인한다. fixture를 더하거나 범위를 바꾸면 계약 테스트도 함께 바꾼다. 계약 테스트 없이 값을 뽑는 fixture를 두지 않는다. 기반 클래스의 `prepare`는 값을 뽑지 않고 fixture를 부르므로 계약 테스트를 따로 두지 않는다.
