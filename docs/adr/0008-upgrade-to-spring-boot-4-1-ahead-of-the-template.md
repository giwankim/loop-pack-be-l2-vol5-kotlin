---
status: accepted
date: 2026-10-01
---

# 과정 템플릿보다 앞서 Spring Boot 4.1로 올린다

과정 템플릿은 Spring Boot 3.4.4, Kotlin 2.0.20, Gradle 8.13을 쓴다. 3.4.x의 오픈소스 지원은 2025-12-31에 끝났고, 마지막 3.x인 3.5.x의 지원도 2026-06-30에 끝났다. Spring Initializr는 이제 4.x 프로젝트만 만든다. 시험 빌드는 Boot 4.1.1, Kotlin 2.3.21, Gradle 9.7.1에서 기존 테스트 419개를 모두 통과했다. 바뀐 것은 43개 파일, 약 150줄이고, 동작을 고쳐야 한 테스트는 없었다. 그래서 4.1로 올리고, 과정이 끝날 때까지 기다리지 않는다.

- 지금이 가장 싸다. 3주차와 테스트 fixture 이전이 아직 시작되지 않았고, 시험 빌드가 바꿀 곳을 범주마다 커밋으로 나눠 두었다.
- 지원이 이어지는 버전으로 돌아간다. 4.1.x의 오픈소스 지원은 2027-07-31까지다.
- 실제 버전 이전을 겪어 보는 것도 이 과정에서 배울 거리다.

지금까지는 템플릿의 Kotlin 2.0.20을 올리지 않았다. 앞으로는 Kotlin, Gradle, 컴파일러 옵션을 고른 Boot 버전에 맞춰 Spring Initializr가 만드는 값으로 맞추고, 라이브러리는 Boot BOM이 관리하는 버전을 따른다. 라이브러리 하나를 맞추려고 그보다 더 올리지 않는다.

- Kotlin 컴파일러 옵션은 Initializr처럼 `-Xjsr305=strict`와 `-Xannotation-default-target=param-property`를 모든 모듈에 건다.
- 배치 메타데이터는 지금처럼 DB에 둔다(`spring-boot-starter-batch-jdbc`). Spring Batch 6이 시퀀스 이름을 바꿨지만(`BATCH_JOB_SEQ` → `BATCH_JOB_INSTANCE_SEQ`) 옮길 실제 DB가 없어 마이그레이션은 두지 않는다.
- JSON 본문 뒤에 다른 내용이 붙으면(`{…}xyz`) Jackson 3의 기본값대로 400으로 거절한다. 지금은 이 경우에 필수 필드가 빠졌다는 엉뚱한 메시지가 나가므로 메시지를 바로잡는다. `use-jackson2-defaults`는 켜지 않는다.
- ktlint는 1.8.0으로 올리고 Gradle 플러그인은 최신판(14.2.0)을 쓴다. 1.8.0이 새로 내는 지적 138건 중 134건이 클래스 시그니처의 공백이라 `class-signature` 규칙은 끄고, 나머지 4건은 손으로 고친다.
- 확인된 후속판이 있는 라이브러리는 옮긴다: instancio-junit 6, archunit-junit6, 그리고 Initializr가 쓰는 테스트 스타터. testcontainers-redis는 Testcontainers 2용 판이 없어 일반 컨테이너로 바꾼다.
- QueryDSL 5.1.0은 Boot가 관리하는 동안 그대로 쓴다. logback-slack-appender는 보관(archived) 상태지만 Boot 4에서도 동작해 그대로 둔다.

## 고르지 않은 것

- **과정이 끝날 때까지 3.4에 머문다.** 주마다 내는 PR이 과제에만 집중되고 템플릿 코드와도 계속 맞는다. 하지만 지원이 끝난 버전에 남고, 나중의 이전은 그사이 쌓인 코드만큼 비싸진다.
- **3.5.x로만 올린다.** Kotlin을 덜 올려도 되지만 3.5.x도 지원이 끝났다.
- **4.0.x로 올린다.** Kotlin 2.2로 충분하지만 지원이 2026-12-31에 끝나고, instancio-kotlin은 Kotlin 메타데이터 2.3 이상으로 빌드되어 받을 수 없다.

## 대가

- 이 저장소의 플랫폼이 과정 템플릿과 달라진다. 다음 `giwankim` PR에 업그레이드가 함께 실리고, 리뷰어와 다른 수강생의 코드는 Boot 3 기준이다.
- 템플릿에 나중에 들어오는 코드, 예를 들어 이후 주차의 Kafka나 Batch 예제는 Boot 3 API로 쓰여 있을 것이라 옮겨 와야 한다.
- 남겨 둔 것이 있다. Kafka 송수신에는 테스트가 없고, 빌드 스크립트에는 Gradle 10에서 깨질 경고 15건이 남는다. 로그에 trace ID가 찍히는지는 한 번 손으로 확인할 뿐이고, 테스트가 지키지 않는다.

조사: [Spring Boot 4 업그레이드 조사](../research/spring-boot-4-upgrade.md). 시험 빌드: fork의 `prototype/boot4` 브랜치([PROTOTYPE-boot4.md](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/bd84e9f0cf0dabda1c78687b43ab5f2aaf7c8598/PROTOTYPE-boot4.md)).
