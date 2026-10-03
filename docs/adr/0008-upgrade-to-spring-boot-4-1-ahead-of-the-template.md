---
status: accepted
date: 2026-10-01
---

# 과정 템플릿보다 앞서 Spring Boot 4.1과 Java 25로 올린다

과정 템플릿은 Spring Boot 3.4.4, Kotlin 2.0.20, Gradle 8.13, Java 21을 쓴다. 3.4.x의 오픈소스 지원은 2025-12-31에 끝났고, 마지막 3.x인 3.5.x의 지원도 2026-06-30에 끝났다. Spring Initializr는 이제 4.x 프로젝트만 만든다. 시험 빌드는 Boot 4.1.1, Kotlin 2.3.21, Gradle 9.7.1에서 기존 테스트 419개를 모두 통과했다. Java 21과 Java 25 모두에서 통과했다. 바뀐 것은 43개 파일, 약 150줄이고, 동작을 고쳐야 한 테스트는 없었다. Java 25로 옮기는 데에는 toolchain 두 줄만 바꾸면 됐다. 그래서 4.1과 Java 25로 올리고, 과정이 끝날 때까지 기다리지 않는다.

- 지금이 가장 싸다. 3주차와 테스트 fixture 이전이 아직 시작되지 않았고, 시험 빌드가 바꿀 곳을 범주마다 커밋으로 나눠 두었다.
- 지원이 이어지는 버전으로 돌아간다. 4.1.x의 오픈소스 지원은 2027-07-31까지다.
- 실제 버전 이전을 겪어 보는 것도 이 과정에서 배울 거리다.

지금까지는 템플릿의 Kotlin 2.0.20을 올리지 않았다. 앞으로는 Kotlin, Gradle, 컴파일러 옵션을 고른 Boot 버전에 맞춰 Spring Initializr가 만드는 값으로 맞추고, 라이브러리는 Boot BOM이 관리하는 버전을 따른다. 라이브러리 하나를 맞추려고 그보다 더 올리지 않는다. Java는 Initializr가 고를 수 있게 내놓는 버전 가운데 LTS인 25를 쓴다.

- Kotlin 컴파일러 옵션은 Initializr처럼 `-Xjsr305=strict`와 `-Xannotation-default-target=param-property`를 모든 모듈에 건다.
- Gradle과 빌드는 같은 JDK 25를 쓴다. 모든 모듈의 toolchain이 25이고, `.sdkmanrc`는 Gradle이 뜨는 JVM을 같은 JDK 25로 고정한다. JDK를 내려받는 플러그인은 두지 않는다. kapt, JaCoCo, Mockito, Hibernate는 Java 25에서 따로 설정할 것이 없다. Gradle 9.7.1의 기본 JaCoCo 0.8.14가 Java 25를 지원한다.
- 배치 메타데이터는 지금처럼 DB에 둔다(`spring-boot-starter-batch-jdbc`). Spring Batch 6이 시퀀스 이름을 바꿨지만(`BATCH_JOB_SEQ` → `BATCH_JOB_INSTANCE_SEQ`) 옮길 실제 DB가 없어 마이그레이션은 두지 않는다.
- JSON 본문 뒤에 다른 내용이 붙으면(`{…}xyz`) Jackson 3의 기본값대로 400으로 거절한다. 지금은 이 경우에 필수 필드가 빠졌다는 엉뚱한 메시지가 나가므로 메시지를 바로잡는다. `use-jackson2-defaults`는 켜지 않는다.
- ktlint는 1.8.0으로 올리고 Gradle 플러그인은 최신판(14.2.0)을 쓴다. 1.8.0이 새로 내는 지적 136건 중 134건이 `class-signature` 규칙의 것(시그니처 공백 132건, 상위 타입 줄바꿈 2건)이라 그 규칙은 끄고, 나머지 2건(`ProductRepositoryImpl.kt`의 `when` 분기 사이에 빠진 빈 줄)은 손으로 고친다.
- 확인된 후속판이 있는 라이브러리는 옮긴다: instancio-junit 6, archunit-junit6, 그리고 Initializr가 쓰는 테스트 스타터. testcontainers-redis는 Testcontainers 2용 판이 없어 일반 컨테이너로 바꾼다.
- QueryDSL 5.1.0은 Boot가 관리하는 동안 그대로 쓴다. logback-slack-appender는 보관(archived) 상태지만 Boot 4에서도 동작해 그대로 둔다.

## 고르지 않은 것

- **과정이 끝날 때까지 3.4에 머문다.** 주마다 내는 PR이 과제에만 집중되고 템플릿 코드와도 계속 맞는다. 하지만 지원이 끝난 버전에 남고, 나중의 이전은 그사이 쌓인 코드만큼 비싸진다.
- **3.5.x로만 올린다.** Kotlin을 덜 올려도 되지만 3.5.x도 지원이 끝났다.
- **4.0.x로 올린다.** Kotlin 2.2로 충분하지만 지원이 2026-12-31에 끝나고, instancio-kotlin은 Kotlin 메타데이터 2.3 이상으로 빌드되어 받을 수 없다.
- **Java 27로 올린다.** Initializr가 내놓지만 LTS가 아니다.
- **JDK를 내려받는 Gradle 플러그인(foojay)을 둔다.** JDK가 없는 기계에서도 빌드가 되지만, Initializr가 만들지 않는 플러그인이고 JDK를 말없이 내려받는다.

## 대가

- 이 저장소의 플랫폼이 과정 템플릿과 달라진다. 다음 `giwankim` PR에 업그레이드가 함께 실리고, 리뷰어와 다른 수강생의 코드는 Boot 3 기준이다.
- 템플릿에 나중에 들어오는 코드, 예를 들어 이후 주차의 Kafka나 Batch 예제는 Boot 3 API로 쓰여 있을 것이라 옮겨 와야 한다.
- 빌드하는 기계와 부트 jar를 실행하는 쪽 모두 Java 25가 있어야 한다. JDK 25가 없는 기계에서는 빌드가 toolchain을 찾지 못해 멈춘다.
- 남겨 둔 것이 있다. Kafka 송수신에는 테스트가 없고, 빌드 스크립트에는 Gradle 10에서 깨질 경고 15건이 남는다. 로그에 trace ID가 찍히는지는 한 번 손으로 확인할 뿐이고, 테스트가 지키지 않는다.

조사: [Spring Boot 4 업그레이드 조사](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/f685ee017279c69934efb08f310986fc9d739664/docs/research/spring-boot-4-upgrade.md). 시험 빌드: fork의 `prototype/boot4` 브랜치([PROTOTYPE-boot4.md](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/bd84e9f0cf0dabda1c78687b43ab5f2aaf7c8598/PROTOTYPE-boot4.md)).
