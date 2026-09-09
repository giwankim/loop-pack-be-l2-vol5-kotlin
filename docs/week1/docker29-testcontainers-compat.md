# Docker Engine 29 와 Testcontainers 1.20.6 호환 문제 기록 (Week 1)

| 항목 | 값 |
| --- | --- |
| 작성일 | 2026-09-09 |
| 증상 | 모든 `@SpringBootTest` 가 컨텍스트 로드 단계에서 실패. 데몬 응답: `client version 1.32 is too old. Minimum supported API version is 1.40` |
| 확인한 환경 | Docker Desktop 4.90.0 (Engine 29.7.2, API 1.55, 최소 허용 1.40), Spring Boot 3.4.4, Testcontainers 1.20.6, docker-java 3.4.1 (Testcontainers jar 안에 shaded), Gradle 8.13 |
| 원인 | Testcontainers 1.20.6 은 API 버전을 따로 정해 주지 않으면 1.32 로 고정하고 Docker Engine 29 는 1.40 미만을 거부한다 |
| 적용한 조치 | `~/.docker-java.properties` 에 `api.version=1.44` (저장소 밖) |
| 제품 코드 변경 | 없음 |
| 빌드 파일 변경 | 없음 |

이 문서는 1주차 테스트를 돌리다 만난 Docker 호환 문제를 어디까지 확인했고 무엇을 근거로 조치했는지 남긴다. 추측은 빼고 바이트코드와 재현 결과로 확인한 사실만 적는다.

## 1. 증상

`./gradlew :apps:commerce-api:test` 를 돌리면 테스트 클래스마다 Spring 컨텍스트 로드가 실패한다. 예외 사슬은 다음과 같다.

```text
ExceptionInInitializerError                  (MySqlTestContainersConfig companion 초기화)
  └ ContainerLaunchException                 (GenericContainer.java:351)
      └ RetryCountExceededException          (Unreliables.java:88)
          └ ContainerLaunchException         (GenericContainer.java:556)
              └ BadRequestException          (DefaultInvocationBuilder.java:237)
                Status 400: {"message":"client version 1.32 is too old.
                  Minimum supported API version is 1.40, please upgrade your client to a newer version"}
```

첫 줄에 `ExceptionInInitializerError` 가 오는 것은 `modules/jpa/src/testFixtures/kotlin/com/loopers/testcontainers/MySqlTestContainersConfig.kt` 가 companion object 초기화 블록 안에서 `MySQLContainer.start()` 를 부르기 때문이다. Docker 와의 핸드셰이크 실패가 설정 클래스의 초기화 실패 모양으로 드러난다. 메시지에 적힌 대로 진짜 문제는 클라이언트가 보내는 API 버전 1.32 다.

## 2. 원인 사슬

### 2.1 어떤 버전이 실제로 쓰이는가

저장소는 Testcontainers 버전을 직접 적지 않는다. `gradle.properties` 의 `springBootVersion=3.4.4` 가 Spring Boot BOM 을 고르고 BOM 이 Testcontainers 버전을 고른다. `dependencyInsight` 로 확인한 결과다.

```text
$ ./gradlew :apps:commerce-api:dependencyInsight --configuration testRuntimeClasspath \
    --dependency org.testcontainers:testcontainers
org.testcontainers:testcontainers:1.20.6 (selected by rule)
+--- org.springframework.boot:spring-boot-testcontainers:3.4.4
+--- org.testcontainers:mysql:1.20.6            <- project :modules:jpa
\--- org.testcontainers:junit-jupiter:1.20.6

$ ./gradlew ... --dependency com.github.docker-java:docker-java-api
com.github.docker-java:docker-java-api:3.4.1
\--- org.testcontainers:testcontainers:1.20.6
```

Testcontainers 1.x 는 docker-java 의 core 패키지를 자기 jar 안에 `org.testcontainers.shaded.com.github.dockerjava.core` 로 복사해 둔다(shading). 따라서 아래에서 말하는 docker-java 동작은 별도 artifact 가 아니라 `testcontainers-1.20.6.jar` 안의 클래스에서 읽었다.

### 2.2 Testcontainers 1.20.6 은 버전을 모르면 1.32 로 고정한다

`org.testcontainers.dockerclient.DockerClientProviderStrategy.getClientForConfig(TransportConfig)` 를 `javap -c -p` 로 읽은 결과다.

```text
130: invokestatic  DefaultDockerClientConfig.createDefaultConfigBuilder()
135: invokevirtual DefaultDockerClientConfig$Builder.build()
138: invokevirtual DefaultDockerClientConfig.getApiVersion()
141: getstatic     RemoteApiVersion.UNKNOWN_VERSION
144: if_acmpne     155
148: getstatic     RemoteApiVersion.VERSION_1_32
151: invokevirtual DefaultDockerClientConfig$Builder.withApiVersion(RemoteApiVersion)
```

Java 로 옮기면 다음과 같다.

```java
DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
if (builder.build().getApiVersion() == RemoteApiVersion.UNKNOWN_VERSION) {
    builder.withApiVersion(RemoteApiVersion.VERSION_1_32);
}
```

`api.version` 을 어디에서도 정해 주지 않으면 docker-java 는 `UNKNOWN_VERSION` 을 돌려주고 Testcontainers 는 그 자리에 1.32 를 넣는다. 이후 모든 요청은 `/v1.32/...` 경로로 나간다. 데몬과 버전을 협상하는 단계는 없다.

같은 메서드를 Testcontainers 2.0.5 에서 읽어 보면 `VERSION_1_44` 가 들어간다. 2.x 는 fallback 값을 바꿔서 이 문제를 피한다.

### 2.3 Docker Engine 29 는 1.40 미만을 거부한다

| Docker Engine | 날짜 | 데몬이 받는 최소 API 버전 |
| --- | --- | --- |
| 29.0.0 | 2025-11-10 | 1.44 로 올림 (release notes: "The daemon now requires API version v1.44 or later") |
| 29.3.0 | 2026-03-05 | 1.40 으로 내림 (release notes: "Lower minimum API version from v1.44 to v1.40") |
| 29.7.2 (이 머신) | | 1.40 (`docker version` 출력의 `minimum version 1.40`) |

1.32 는 어느 29.x 에서도 최소값 아래다. 데몬은 컨테이너를 만들기 전에 400 을 돌려준다.

### 2.4 정리

```text
gradle.properties (springBootVersion=3.4.4)
  → BOM 이 Testcontainers 1.20.6 을 선택
    → api.version 미설정 → docker-java 가 UNKNOWN_VERSION 반환
      → Testcontainers 가 VERSION_1_32 로 고정
        → Docker Engine 29.7.2 (최소 1.40) 가 400 으로 거부
          → MySQLContainer.start() 실패 → 모든 @SpringBootTest 컨텍스트 로드 실패
```

## 3. 재현과 검증

같은 테스트를 조치 파일 유무만 바꿔 두 번 돌렸다. 다른 변수는 바꾸지 않았다.

```bash
# 1) 파일을 치우고 실행
mv ~/.docker-java.properties ~/.docker-java.properties.bak
./gradlew :apps:commerce-api:test --tests com.loopers.CommerceApiContextTest --rerun

# 2) 되돌리고 같은 테스트 실행
mv ~/.docker-java.properties.bak ~/.docker-java.properties
./gradlew :apps:commerce-api:test --tests com.loopers.CommerceApiContextTest --rerun
```

| 실행 | `~/.docker-java.properties` | 결과 | 확인 위치 |
| --- | --- | --- | --- |
| 1 | 없음 | 실패. `client version 1.32 is too old ... 1.40` | `apps/commerce-api/build/test-results/test/TEST-com.loopers.CommerceApiContextTest.xml` |
| 2 | 있음 (`api.version=1.44`) | 성공. tests=1, failures=0, errors=0 | 같은 파일 |

`--rerun` 은 Gradle 이 up-to-date 로 판단해 테스트를 건너뛰지 않게 하려고 붙였다. 테스트 JVM 은 매번 새로 fork 되므로 홈 디렉터리 파일을 바꾸면 바로 반영된다.

## 4. 적용한 조치

### 4.1 파일 내용

```properties
# ~/.docker-java.properties
api.version=1.44
```

### 4.2 이 파일이 읽히는 이유와 순서

docker-java 3.4.1 의 `DefaultDockerClientConfig.createDefaultConfigBuilder()` 는 `api.version` 을 아래 순서로 읽고 뒤에 오는 출처가 앞의 값을 덮어쓴다. 바이트코드의 문자열 상수로 확인했다.

| 순서 | 출처 | 비고 |
| --- | --- | --- |
| 1 | 클래스패스의 `/docker-java.properties` | 테스트 리소스에 두면 저장소 단위로 적용된다 |
| 2 | `${user.home}/.docker-java.properties` | 이번에 사용. 머신 단위로 적용된다 |
| 3 | JVM 시스템 프로퍼티 `-Dapi.version` | 셸 한 번만 쓸 때는 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` |
| - | 환경 변수 | `api.version` 용 환경 변수는 없다. 환경 변수로는 `DOCKER_HOST`, `DOCKER_TLS_VERIFY`, `DOCKER_CERT_PATH`, `DOCKER_CONFIG`, `DOCKER_CONTEXT` 만 읽는다 |

값을 정해 두면 `getApiVersion()` 이 `UNKNOWN_VERSION` 을 돌려주지 않으므로 2.2 의 분기가 1.32 를 넣지 않는다.

`~/.testcontainers.properties` 도 있지만 `docker.client.strategy` 만 들어 있고 API 버전과는 무관하다.

### 4.3 왜 1.44 인가

- 이 데몬이 받는 범위 1.40 ~ 1.55 안에 있다.
- docker-java 3.4.1 의 `RemoteApiVersion` 에 `VERSION_1_44` 상수가 이미 있다.
- Testcontainers 2.0.5 가 fallback 으로 쓰는 값과 같다 (2.2 에서 바이트코드로 확인).

### 4.4 왜 저장소 밖에 두었는가

1주차 과제에서는 빌드 파일과 의존성을 바꾸면 안 된다. Testcontainers 나 Spring Boot 를 올리는 방법은 그 조건에 걸린다. 홈 디렉터리 파일은 저장소를 건드리지 않으면서 원인 지점(2.2 의 분기)을 정확히 비켜 간다.

## 5. 대안과 upstream 상태

| 방법 | 저장소 변경 | 효과 범위 | 비고 |
| --- | --- | --- | --- |
| `~/.docker-java.properties` (현재) | 없음 | 이 머신 | 팀원과 CI 는 각자 같은 파일이 필요하다 |
| 테스트 리소스에 `docker-java.properties` 추가 (예: `modules/jpa/src/testFixtures/resources/`) | 파일 1개, 빌드 파일 아님 | 저장소를 받은 모두 | 4.2 의 순서상 홈 파일과 공존할 수 있다. 과제 조건에 맞는지는 별도로 판단한다 |
| Testcontainers 1.21.4 이상 또는 Spring Boot 3.5.9 이상으로 올림 | 빌드 파일 | 근본 해결 | 이번 과제 조건에서는 불가 |

upstream 쪽 상황은 이렇다.

- Testcontainers 1.21.4 (2025-12-15) 가 1.x 계열의 수정판이다. release note 는 "This release makes version 1.21.x works with recent Docker Engine changes" 한 줄뿐이다.
- Testcontainers 2.x 는 2.0.5 에서 fallback 이 `VERSION_1_44` 인 것을 바이트코드로 확인했다.
- Spring Boot 3.5.9 가 Testcontainers 1.21.4 로 올렸다 (spring-boot issue #48542, milestone 3.5.9).
- Spring Boot 3.4.x 가 수정판을 받은 기록은 찾지 못했다. 이 저장소가 쓰는 3.4.4 는 1.20.6 을 고르므로 Docker Engine 29 머신에서는 항상 이 문제를 만난다.

## 6. 참고

- Docker Engine 29 release notes: https://docs.docker.com/engine/release-notes/29/
- Testcontainers 1.21.4 release: https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4
- testcontainers-java issue #11235 (Docker engine 29 is no longer compatible): https://github.com/testcontainers/testcontainers-java/issues/11235
- spring-boot issue #48104 (Docker 29.0.0 breaks Testcontainers integration): https://github.com/spring-projects/spring-boot/issues/48104
- spring-boot issue #48542 (Upgrade to Testcontainers 1.21.4, milestone 3.5.9): https://github.com/spring-projects/spring-boot/issues/48542
