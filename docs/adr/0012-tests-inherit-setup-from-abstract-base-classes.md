---
status: accepted
date: 2026-10-07
---

# 테스트는 추상 기반 클래스에서 설정과 데이터 준비를 물려받는다

테스트가 데이터를 준비하는 코드가 테스트마다 펼쳐져 있다. `productRepository.save(createProduct(`가 168곳, `brandRepository.save(createBrand(`가 145곳, `userRepository.save(User(`가 67곳이고, 자기 슬라이스의 Service로 등록하는 줄도 103곳이다. 설정도 되풀이된다. MockMvc 테스트 11개가 같은 애노테이션 세 줄을, `@DataJpaTest` 7개가 비슷한 세 줄을 적는다. 토비의 [splearn](https://github.com/tobyspringboot/splearn-1-part2/tree/2d1acdffdd0ad2aa4d400d0ed84544fd07ca0401)과 그 Kotlin 이식([giwankim/splearn](https://github.com/giwankim/splearn/tree/008d06d180d0237635c833e1c14a7b98a62cc99d))을 따라, 추상 기반 클래스가 테스트 설정과 `prepare` 메서드를 함께 지고 테스트는 그것을 상속한다. 지금까지는 다른 슬라이스의 데이터를 그 슬라이스의 저장소로 준비했다([ADR 0007](./0007-fixtures-build-through-constructors.md)). 다른 슬라이스의 등록 규칙이 바뀌어도 상관없는 테스트가 깨지지 않게 하려는 것이었다. 준비가 기반 클래스 한 곳에 모이면 그 규칙이 바뀌어도 고칠 곳은 `prepare` 메서드 하나다. 이 까닭으로 저장소를 고집할 필요가 없어지므로, 준비 규칙을 "어느 슬라이스의 데이터인가"에서 "어떤 상태가 필요한가"로 옮긴다.

- 기반 클래스는 셋이고 `com.loopers.support.test`에 둔다. 테스트 설정 애노테이션은 모두 기반 클래스가 진다.
  - `BaseApplicationServiceTest`: `@SpringBootTest`, `@Import(MySqlTestContainersConfig, RedisTestContainersConfig)`, `@Transactional`. `@ApplicationServiceTest` 스테레오타입은 여기에 들어가고 없어진다.
  - `BaseWebApiAdapterTest : BaseApplicationServiceTest`: `@AutoConfigureMockMvc`, `@Import(AdminSecurityConfig)`. `prepare` 메서드는 위에서 물려받는다.
  - `BaseRepositoryTest`: `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)`, `@Import(DataSourceConfig, MySqlTestContainersConfig)`. Boot 4.1.1의 기본값 `NON_TEST`는 자동 구성되어 테스트 DB에 닿는 DataSource만 그대로 둔다. `DataSourceConfig`의 `HikariDataSource`는 직접 만든 빈이라 내장 DB로 바뀌려 하고, 내장 DB가 클래스 경로에 없어 컨텍스트가 뜨지 않는다. QueryDSL 테스트는 `QueryDslConfig`와 어댑터를 따로 가져온다.
- 서비스·MockMvc·저장소 테스트는 모두 자기 종류의 기반 클래스를 상속한다. 하위 클래스가 더하는 애노테이션은 추가 `@Import`와 트랜잭션에서 빠지는 표시뿐이다. 기반 클래스의 `@Import`는 하위 클래스의 것과 합쳐지고, 같은 설정이면 컨텍스트 캐시 키도 그대로다(실험으로 확인했다). `CommerceApiContextTest`와 domain 테스트는 상속하지 않는다. 다만 저장한 상품이 있어야 하는 fixture 계약 테스트(`OrderFixturesTest`)는 domain 패키지에 있어도 `@DataJpaTest`이므로 `BaseRepositoryTest`를 상속한다.
- 테스트는 자기 슬라이스든 다른 슬라이스든 데이터를 `prepare<Type>`과 변경 도우미로 준비한다. 포트를 직접 부르는 것은 검증하는 동작과 그 결과를 읽는 단언뿐이다. 한 클래스만 쓰는 준비 도우미(`prepareBrandRegisteredAt` 등)는 그 클래스에 둔다.
  - `BaseApplicationServiceTest`의 `prepare`는 그 슬라이스의 provided 포트가 만들 수 있는 상태를 포트로 만든다. 포트가 `ProductInfo`·`OrderInfo`를 돌려주므로 엔티티는 ID로 다시 읽는다. (2026-10-08, [ADR 0014](./0014-finders-load-whole-aggregates.md): `OrderCreator`가 `Order`를 돌려주게 되어, 다시 읽는 것은 `ProductInfo`를 돌려주는 상품뿐이다.) 포트가 없는 데이터(사용자, 포인트 계정)와 포트가 막는 상태(살아 있는 상품이 남은 삭제된 브랜드)는 저장소로 만든다.
  - `BaseRepositoryTest`의 `prepare`는 저장소로 만든다. 저장소 테스트의 컨텍스트에는 포트가 없다.
- `prepare`가 다른 애그리거트를 받는 파라미터의 기본값은 저장한 엔티티다(`prepareProduct(brand: Brand = prepareBrand())`). fixture는 계속 기본값 없이 받는다. 저장하지 않은 기본값이 저장을 실패하게 한다는 ADR 0010의 까닭은 저장한 기본값에는 해당하지 않는다.
- 마지막으로 준비한 엔티티를 기반 클래스의 `protected lateinit var` 필드에 둔다. 다른 `prepare`의 기본값으로 불린 `prepare`도 필드를 바꾸며, 나중 것이 남는다. 테스트는 기본값으로 만든 것까지 세어 그 타입의 엔티티를 하나만 준비했을 때만 필드를 읽고, 그렇지 않으면 반환값을 쓴다.
- 준비한 데이터를 준비 도중에 바꾸는 도우미(`deleteProduct`, `updateProduct`, `updateProductStock`)도 포트를 거치고, 대상의 기본값은 필드다. `prepareDeleted…` 같은 변형을 두지 않고 `prepare`와 변경 도우미를 잇는다. 포트가 막는 상태는 우회를 이름에 드러낸 저장소 도우미(`deleteBrandKeepingProducts`)가 명시적으로 저장해 만든다. 테스트 트랜잭션 없이도 남아야 하기 때문이다. 도우미는 처음 쓰는 테스트가 생길 때 더한다.
- `UserFixture`는 없어지고 `prepareUser()`·`prepareUserWithoutAccount()`가 그 일을 한다. 기반 클래스는 `entityManager`를, `BaseWebApiAdapterTest`는 `mvc`를 `protected` 필드로 준다.
- 기반 클래스는 테스트 트랜잭션을 건다. 요청마다 커밋된 결과를 다음 요청이 읽어야 하는 주문 MockMvc 테스트 셋은 클래스에 `@Transactional(propagation = NOT_SUPPORTED)`를 달아 빠진다. 이 표시를 빠뜨리면 세 클래스가 스스로 실패한다(실험으로 확인했다). 그중 스키마를 다시 만드는 테스트 하나는 실패하지 않고 멈춘다. 테스트 트랜잭션이 쥔 메타데이터 잠금을 스키마 도구의 다른 연결이 기다리는데, MySQL의 `lock_wait_timeout` 기본값이 1년이라 풀리지 않는다. 그래서 테스트 MySQL 컨테이너에 `--lock-wait-timeout=10`을 준다.
- `PointChargerTransactionTest`는 지운다. 그 단언은 테스트 트랜잭션이 감싸도 통과해서 커밋을 확인하지 못했다. 충전이 커밋된다는 것은 `OrderConfirmationApiTest`가 충전 뒤 새 요청으로 잔액을 읽어 지킨다.
- 쿼리 수를 세는 테스트는 `EntityManager.withStatistics { }`(jpa `testFixtures`, `statistics` 옆)로 통계를 켜고 끈다. 두 기반 계층이 함께 쓰고 데이터를 준비하지 않으므로 기반 클래스에 두지 않는다.

## 고르지 않은 것

- **ADR 0007의 규칙을 지키고 저장소로 준비하는 기반 하나를 둔다.** 모든 저장소가 세 종류 테스트의 컨텍스트에 있으므로 기반 하나로 충분하다. 그러나 자기 슬라이스의 데이터는 Service로 준비해야 해서, 같은 `prepareProduct`를 상품 슬라이스의 테스트는 쓸 수 없다. 메서드는 부르는 쪽의 슬라이스를 모른다. 자기 슬라이스를 준비하는 103줄이 그대로 남는다.
- **splearn처럼 `prepare`를 모두 포트로 만든다.** 사용자와 포인트 계정을 만드는 포트가 없고, 데이터 어긋남을 막는 테스트가 기대는 상태(살아 있는 상품이 남은 삭제된 브랜드)를 포트가 막는다.
- **테스트마다 포트와 저장소를 고른다.** 준비하는 방법이 두 가지로 남는다(ADR 0007).
- **설정은 스테레오타입이 지고, 기반 클래스는 쓰고 싶은 테스트만 상속한다.** splearn의 방식이다. 상속하지 않는 테스트가 있으면 한 종류의 테스트를 설정하는 길이 둘이 되고, splearn처럼 기반과 하위 클래스에 같은 애노테이션이 겹친다.
- **`prepare`가 반환값만 주고 필드를 두지 않는다.** 덮어쓰기가 없다(splearn 이식의 [`247e927`](https://github.com/giwankim/splearn/commit/247e927e693dc78001fe4cbcf457a4caab29eaee)이 고친 버그다). 그러나 준비한 엔티티를 테스트 곳곳에서 바로 쓰는 편의를 버린다.
- **준비 도우미를 Spring 빈으로 주입한다(`UserFixture`의 방식).** `@DataJpaTest`는 컴포넌트를 스캔하지 않아 저장소 테스트에 닿지 않는다. 기반 클래스의 `@Autowired` 필드는 세 종류 모두에서 주입된다.
- **기반 클래스가 테스트 트랜잭션을 걸지 않는다.** 트랜잭션을 쓰는 클래스 18개가 `@Transactional`을 따로 적어야 한다. 빠지는 셋이 그 사실을 적는 편이 짧다.
- **splearn의 `prepareStatistics()`.** 통계를 켜기만 하고 끄지 않는다. 여기서는 여러 클래스가 컨텍스트를 나눠 쓰므로 뒤의 테스트로 샌다.

## 대가

- 포트가 깨지면 그 포트로 준비하는 테스트도 함께 실패한다. `ProductRegister`의 등록이 깨지면 상품을 준비하는 서비스·MockMvc 클래스 약 11개가 함께 붉어지고, 실패가 한 슬라이스를 가리키지 않는다.
- 필드는 숨은 통로다. 기본값으로 불린 `prepare`가 테스트가 앞서 준비한 엔티티를 덮어쓸 수 있다. 위의 읽기 규칙이 막고, `/code-review`가 `CODING_STANDARDS.md`에 비추어 지킨다.
- 테스트의 설정이 클래스 선언에 보이지 않는다. 상위 클래스의 이름이 테스트의 종류를 말하고, 트랜잭션에서 빠지는 클래스만 그 사실을 적는다.
- 테스트 트랜잭션이 없는 클래스에서 다시 읽은 엔티티는 분리되어 있다. 지연 연관(`Product.brand`)을 건드리면 실패한다.
- `CODING_STANDARDS.md`의 테스트 도우미·fixture 규칙을 이 결정에 맞춰 고치고, 서비스 10개·MockMvc 11개·저장소 7개 클래스를 모두 옮긴다. 옮기는 동안에는 두 방식이 섞여 있다.

## 다시 보는 조건

실제 서버(`RANDOM_PORT`)나 다른 스레드로 요청하는 HTTP 테스트가 생기면 MockMvc에 묶인 `BaseWebApiAdapterTest` 말고 기반이 하나 더 필요하다. 그 테스트는 트랜잭션 롤백으로 정리할 수 없다. DataSource를 `@ServiceConnection`으로 자동 구성하게 되면 `replace = NONE`을 뺀다.

> 2026-10-09 [ADR 0018](./0018-pessimistic-row-locks-in-one-global-order.md)로 HTTP보다 먼저 포트를 다른 스레드에서 부르는 테스트가 생겼다. 행 잠금을 쥔 트랜잭션 곁에서 다른 트랜잭션이 기다리는지 보려면 둘 다 커밋해야 하므로, 롤백하는 기반을 쓸 수 없다. 그래서 기반이 넷이 되었다. `BaseCommittingApplicationServiceTest : BaseApplicationServiceTest`는 클래스에 `@Transactional(propagation = NOT_SUPPORTED)`를 달아 테스트 트랜잭션에서 빠지고, 테스트마다 모든 표를 비운다. 포트를 다른 스레드에서 부르고 끝났는지 묻는 `inAnotherThread`와, 결과를 새 트랜잭션에서 다시 읽는 `inNewTransaction`을 준다. `prepare`는 물려받으므로 준비 규칙은 그대로다. 설정이 같아 컨텍스트도 롤백하는 기반과 나눠 쓴다. 다만 잠금을 쥔 쪽을 멈추려고 Service를 `@MockkSpyBean`으로 spy하는 클래스는 컨텍스트가 하나 더 뜬다. 위 "하위 클래스가 더하는 애노테이션"에 이 spy 필드가 더해진다. 첫 하위 클래스는 `OrderConfirmerConcurrencyTest`다. 위 조건의 HTTP 기반은 아직 필요하지 않다.
>
> 2026-10-09 커밋하는 기반을 쓰는 테스트에 브랜드 삭제의 중간 실패 테스트(`BrandRegisterRollbackTest`)가 더해졌다. 다른 스레드는 없지만, 롤백된 결과를 새 트랜잭션에서 읽어야 하므로 롤백하는 기반을 쓸 수 없다. 저장 단계에서 던지려고 Service 대신 `BrandRepository`를 `@MockkSpyBean`으로 spy한다. 이 spy 필드도 "하위 클래스가 더하는 애노테이션"에 들고, 컨텍스트가 하나 더 뜬다. Spring Data 저장소는 JDK 프록시라 테스트 JVM에 `--add-opens java.base/java.lang.reflect=ALL-UNNAMED`가 있어야 spy가 만들어진다. Kotlin·Spring 컨벤션의 테스트 태스크가 그것을 건다.
>
> 2026-10-09 잠금 실패의 409를 보는 `LockFailureApiTest`가 `BaseWebApiAdapterTest`에서 빠지는 두 번째 까닭을 더했다. 다른 스레드의 트랜잭션이 포트로 행 잠금을 쥔 동안 요청이 그 잠금을 기다려야 하므로, 준비한 데이터와 두 트랜잭션이 모두 커밋해야 한다. 주문 MockMvc 테스트 셋처럼 클래스에 `NOT_SUPPORTED`를 달고 표를 비운다. 요청은 여전히 테스트 스레드에서 MockMvc로 보내고 다른 스레드에서는 포트만 부르므로, 위 조건의 HTTP 기반은 아직 필요하지 않다.
>
> 2026-10-15 `BaseApplicationServiceTest`의 `@Import`에 `SqlCaptureConfig`가 더해져 `@Import(MySqlTestContainersConfig, RedisTestContainersConfig, SqlCaptureConfig)`가 되었다. 이 설정은 테스트용 `StatementInspector`를 `HibernatePropertiesCustomizer`로 설치해, `com.loopers.support`의 `captureSql { … }`이 블록 안에서 현재 스레드가 보낸 SQL을 잡게 한다. 블록 밖에서는 아무것도 적지 않고 SQL도 바꾸지 않는다. 모든 하위 클래스의 `@Import`가 같으므로 컨텍스트는 늘지 않았다(전후 9개). 상속하지 않는 `CommerceApiContextTest`도 같은 세 설정을 가져온다. 빠지면 캐시 키가 갈려 그 테스트만의 컨텍스트가 하나 더 뜬다. 둘의 `@Import`는 함께 바꾼다.
>
> 2026-10-15 상품 목록을 재는 `ProductQueryPerformanceApiTest`가 `@TestInstance(PER_CLASS)`를 단다. 수만 행의 seed를 테스트마다 다시 넣지 않고 `@BeforeAll`에서 한 번 넣어 `@AfterAll`에서 표를 비우려는 것이다. 이것도 "하위 클래스가 더하는 애노테이션"에 든다. 클래스는 `LockFailureApiTest`처럼 테스트 트랜잭션에서 빠진다. seed 뒤의 `ANALYZE TABLE`과 인덱스를 숨겼다 보이게 하는 DDL이 열린 트랜잭션을 암묵으로 커밋하기 때문이다.
>
> 2026-10-15 Redis의 시간 한도를 보는 `RedisTimeoutTest`가 포트 없이 `BaseApplicationServiceTest`를 상속하고, 추가 `@Import`로 `StoppableRedisTestContainersConfig`(redis `testFixtures`)를 더한다. 멈추고 다시 띄우고 걸리게 할 수 있는 Redis 컨테이너를 컨텍스트의 빈으로 띄우고, master와 replica의 주소를 그것으로 바꾼다. 장애 테스트만 가져오므로 그 클래스들의 컨텍스트가 하나 더 뜨고, JVM마다 하나인 Redis는 멈추지 않는다. 두 설정의 `DynamicPropertyRegistrar`가 같은 속성을 쓰면 나중에 등록된 쪽이 이기는데, 하위 클래스의 `@Import`가 기반의 것보다 먼저 처리된다. 그래서 이 설정이 `RedisTestContainersConfig`를 스스로 가져와 그 빈을 자기 빈보다 먼저 등록하게 한다.
>
> 2026-10-15 늦은 채움 테스트(`ProductCacheLateRefillTest`, [ADR 0020](./0020-product-list-cache-keyed-by-a-db-revision-locked-last.md))가 `BaseCommittingApplicationServiceTest`의 하위 클래스로 더해졌다. 앞선 목록 조회의 put을 관리자의 수정이 커밋할 때까지 멈추려고, Service나 저장소 대신 `ProductListCacheConfig`의 `RedisCacheWriter` 빈을 `@MockkSpyBean`으로 spy해 첫 put을 `Pause`에서 멈춘다. 이 spy 필드도 "하위 클래스가 더하는 애노테이션"에 들고, 컨텍스트가 하나 더 뜬다. writer는 AOP 프록시도 JDK 프록시도 아니므로 stub은 spy에 바로 건다.
>
> 2026-10-16 고객 목록 캐시([ADR 0020](./0020-product-list-cache-keyed-by-a-db-revision-locked-last.md))가 기반에 정리 하나를 더했다. `BaseApplicationServiceTest`가 테스트가 끝날 때마다(`@AfterEach`) `RedisCleanUp`으로 테스트 Redis를 비우고, 이것을 넓힌 `BaseWebApiAdapterTest`와 `BaseCommittingApplicationServiceTest`가 물려받는다. Redis를 가져오지 않는 `BaseRepositoryTest`는 비우지 않는다. 캐시 키가 품는 상품 목록 revision이 테스트마다 0에서 시작하므로, 비우지 않으면 뒤 테스트가 같은 키에서 앞 테스트의 목록을 읽는다.
>
> 2026-10-16 `BaseCommittingApplicationServiceTest`가 도우미 둘을 더 준다. 커밋된 상품 목록 revision을 읽는 `currentListRevision()`과, 그 revision을 품은 고객 목록의 캐시 키를 운영의 키 생성기로 만드는 `currentListCacheKey(request)`다. 둘 다 `inNewTransaction` 안에서 읽는다. 캐시의 무효화를 보는 테스트는 롤백하면 revision이 오르지 않아 이 기반을 상속하므로, 클래스 셋이 저마다 두던 같은 읽기를 여기 모았다.
>
> 2026-10-16 "하위 클래스가 더하는 애노테이션"에 HTTP 테스트의 `@MockkSpyBean` 필드도 든다. `ProductCacheDatabaseFailureApiTest`가 공유 MySQL을 멈추지 않고 DB 실패를 보려고 revision 저장소를 spy한다. spy가 컨텍스트를 하나 더 띄우므로 그 장면만 클래스로 떼어 냈다.
>
> 2026-10-27 `BaseApplicationServiceTest`의 `@Import`에 `KafkaTestContainersConfig`(kafka `testFixtures`)가 더해져 `@Import(MySqlTestContainersConfig, RedisTestContainersConfig, KafkaTestContainersConfig, SqlCaptureConfig)`가 되었다. commerce-api가 주문 이벤트 토픽을 선언하므로([ADR 0032](./0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) 규칙 9) 모든 전체 컨텍스트의 `KafkaAdmin`이 뜰 때 broker에 붙는다. fixture를 빠뜨린 컨텍스트가 MySQL처럼 곧바로 실패하도록 `kafka.yml`의 test 프로필은 broker 주소 없이 `spring.kafka.admin.fail-fast`를 켠다. 그 결과 이 설정이 없는 전체 컨텍스트는 뜨지 않으므로 발행을 보는 테스트만이 아니라 기반이 가져온다. 상속하지 않는 `CommerceApiContextTest`와 `OrderPaymentIntegrationTest` 장면 8의 재시작한 컨텍스트도 함께 바꿨다. 모든 기반이 같이 바뀌어 기존 컨텍스트는 갈리지 않았다. 토픽 이름을 덮는 `@TestPropertySource`(`OverriddenOrderEventsTopicTest`)가 "하위 클래스가 더하는 애노테이션"에 들고, 속성이 달라 컨텍스트가 하나 더 뜬다. 그 뒤 테스트 프레임워크의 컨텍스트는 17개다. `@DataJpaTest`인 `BaseRepositoryTest`는 Kafka 자동 설정이 없어 그대로다.
>
> 2026-10-27 [issue 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171)로 "하위 클래스가 더하는 애노테이션"에 Kafka 템플릿의 `@MockkSpyBean` 필드가 든다. 주문 확정 이벤트를 broker에서 읽는 `OrderEventPublishingTest`가 커밋 뒤의 중계가 다른 스레드에서 발행하는지 보려고 `KafkaTemplate`을 spy해 `send`를 부른 스레드를 잡는다. 템플릿은 AOP 프록시가 아니므로 stub은 spy에 바로 건다. 이 클래스는 이미 Service spy와 토픽 이름을 덮는 `@TestPropertySource`로 자기 컨텍스트를 쓰므로 컨텍스트는 늘지 않았다. 같은 티켓에서 커밋하는 기반의 `@RecordApplicationEvents`와 `applicationEvents` 필드를 지웠다. 발행을 보는 장면이 모두 broker를 읽게 되어 쓰는 테스트가 없다.
>
> 2026-10-27 [issue 172](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/172)로 "하위 클래스가 더하는 애노테이션"에 처음으로 spy가 아닌 mock, 곧 Kafka 템플릿의 `@MockkBean` 필드가 든다. 커밋 뒤 발행의 누락 구간을 재현하는 `OrderEventPublishGapTest`가 실제 broker에 아무것도 가지 않게 하려고 중계가 쓰는 `KafkaTemplate`을 바꾸고, `send`가 던지거나 `Pause`에서 멈추게 한다. Spring이 컨텍스트를 띄우며 그 mock에도 템플릿의 생명 주기 콜백(`afterSingletonsInstantiated()`)을 부르므로 `relaxUnitFun = true`로 둔다. `Unit`을 돌려주는 호출만 풀리고 `send`는 stub해야 답한다. mock과 클래스 자신의 토픽 이름 때문에 컨텍스트가 하나 더 뜬다. 같은 티켓에서 broker를 읽는 consumer를 `com.loopers.support`의 `TopicReader`로 옮겼다. broker를 읽는 클래스가 둘이 되어서다.
