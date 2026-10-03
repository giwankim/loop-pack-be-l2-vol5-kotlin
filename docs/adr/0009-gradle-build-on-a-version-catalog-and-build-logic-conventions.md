---
status: accepted
date: 2026-10-01
---

# Gradle 빌드를 버전 카탈로그와 build-logic 컨벤션 플러그인으로 옮긴다

지금은 버전이 `gradle.properties`와 settings의 `eachPlugin`에 흩어져 있다. 공통 설정은 루트 `build.gradle.kts`의 `allprojects`/`subprojects` 블록이 모든 모듈에 한꺼번에 건다. 이 빌드는 Gradle 9.7.1 모범 사례 45개 가운데 17개를 따르지 않는다. 버전을 읽는 코드 14곳은 Gradle 10에서 없어질 API를 쓴다. 그래서 의존성 좌표를 모두 `gradle/libs.versions.toml` 하나에 모으고, 공통 설정은 included build인 `build-logic`의 컨벤션 플러그인 세 개로 옮긴다. 각 모듈은 자기 종류에 맞는 컨벤션을 스스로 적용한다.

- 카탈로그에는 모든 좌표와 플러그인을 둔다. Boot BOM이 버전을 관리하는 라이브러리는 버전 없이(`{ module = "g:a" }`) 적는다.
  - 키는 Gradle의 이름 규칙을 따른다. 마디는 대시로 나누고 마디 안은 camelCase로 쓴다.
  - QueryDSL의 `jakarta` classifier는 TOML로 적을 수 없어, 쓰는 곳에서 `variantOf`로 붙인다.
- 버전을 정하는 방식은 그대로 io.spring.dependency-management 플러그인이다. Initializr가 만드는 방식이고, BOM 버전이 전이 의존성의 요청보다 앞선다.
- 컨벤션은 세 개다.
  - `loopers.kotlin-spring`은 모든 모듈에 쓴다. Kotlin, 툴체인과 컴파일러 옵션, 테스트, JaCoCo, ktlint, 공통 의존성을 맡는다.
  - `loopers.spring-boot-application`은 앱 세 개에 쓴다.
  - `loopers.jpa`는 QueryDSL kapt를 쓰는 모듈 네 개에 쓴다.
- Boot 플러그인은 앱에만 걸고, kapt와 `plugin.jpa`는 `loopers.jpa`에만 건다. 라이브러리 모듈에서는 Boot 플러그인이 해 주던 일을 기본 컨벤션이 맡는다.
  - BOM을 직접 가져온다(카탈로그의 `springBootDependencies`, Boot 플러그인과 같은 버전 키).
  - Kotlin 컴파일에 `javaParameters`를 켠다.
  - javac에 `-parameters`를 걸고, 인코딩이 없으면 UTF-8로 둔다(kapt가 만든 Q 클래스를 javac가 컴파일한다).
- build-logic 스크립트는 typesafe-conventions 플러그인으로 카탈로그를 타입 안전하게 읽고, `plugins {}`에서 `alias(libs.plugins.x)`를 쓴다. 플러그인 버전은 `[plugins]` 한 곳에만 있다. 모듈은 이 플러그인이 만들어 주는 `conventions` 카탈로그로 컨벤션을 적용한다(`alias(conventions.plugins.loopers.jpa)`).
- build-logic은 루트 settings에서 `pluginManagement` 밖의 최상위 `includeBuild`로 넣는다.
- 루트 `build.gradle.kts`는 지운다.
  - 저장소는 settings의 `dependencyResolutionManagement`에 두고, 모듈이 저장소를 따로 선언하면 빌드가 실패하게 한다(`FAIL_ON_PROJECT_REPOS`). repo.spring.io 저장소는 뺀다.
  - `group`은 `gradle.properties`의 기본 `group` 속성으로 둔다.
  - `version`은 지금처럼 git 짧은 해시다.
- build-logic도 ktlint로 검사한다. pre-commit 훅은 두 빌드를 모두 검사한다.
- 옮기는 작업은 해석되는 의존성을 바꾸지 않는다. 9개 모듈의 compile/runtime/testCompile/testRuntime·testFixtures 클래스패스와 컴파일된 클래스 파일이 옮기기 전과 같아야 한다. 어느 모듈도 쓰지 않는 공통 의존성(루트 공통 선언 16개 중 10개)은 따로 정리한다.
- build-logic은 Gradle에 내장된 Kotlin 2.4.0으로 컴파일된다. ADR 0008의 "Initializr를 따른다"는 우리 코드를 컴파일하는 Kotlin(2.3.21)에 대한 규칙이다. build-logic의 컴파일러는 Gradle 버전이 정하고, Gradle 버전은 이미 Initializr를 따른다.

## 고르지 않은 것

- **BOM을 `platform(SpringBootPlugin.BOM_COORDINATES)`로 가져온다.** Gradle의 기본 방식이고, Gradle이 권하는 "카탈로그로 선언하고 플랫폼으로 강제한다"에 맞는다.
  - 시험해 보니 좌표 14개가 올라갔다. 모든 모듈의 테스트 클래스패스에서 mockito-core가 5.14.0에서 5.23.0으로, slf4j가 2.0.19로 올랐다. commerce-api에서는 Jackson 2가 springdoc을 거쳐 2.22.x로, JUnit이 archunit을 거쳐 6.1.3으로 올랐다.
  - 테스트 429개는 모두 통과했지만, slf4j·Jackson 2·JUnit은 BOM보다 높아져 "라이브러리는 BOM을 따른다"에 어긋난다.
  - 그 대신 다음 비용을 받아들인다. 모든 구성을 해석하는 데 약 1.2초가 더 들고, 플러그인이 Kotlin 컴파일러와 ktlint의 도구 클래스패스까지 BOM 버전으로 바꾼다. 또 이 플러그인의 문서는 Gradle 8까지만 적는다.
- **`buildSrc`.** 바뀔 때마다 빌드 전체가 out-of-date가 되고, Gradle도 included build를 권한다.
- **문서화된 문자열 API(`libs.findLibrary("x").get()`).** 공개 API만 쓰지만 타입 안전하지 않다. precompiled 스크립트의 `plugins {}`에서는 카탈로그를 쓸 수 없어, 플러그인 버전을 따로 관리해야 한다.
- **지금처럼 모든 모듈에 Boot 플러그인과 kapt를 걸고 앱이 아닌 모듈에서 `bootJar`를 끈다.** 컨벤션이 없애려는 우회를 그대로 옮겨 올 뿐이다.
- **이번에 함께 하지 않는 것:**
  - configuration cache, build cache, UTF-8, wrapper 체크섬 설정;
  - Gradle 9.8(Initializr가 Boot 4.1.1에 9.7.1을 고른다);
  - kapt에서 KSP로 옮기기(QueryDSL 5는 kapt 프로세서다);
  - 빈 컨테이너 프로젝트(`:apps`, `:modules`, `:supports`) 경로 정리;
  - TestKit 테스트. 컨벤션은 서드파티 플러그인을 설정할 뿐이어서 메인 빌드가 곧 테스트다.

## 대가

- typesafe-conventions는 Gradle 내부 API(`LibrariesSourceGenerator`, `SettingsInternal` 등)로 접근자를 만든다. `plugins {}`의 `alias(…)`는 정규식으로 `id(…)`로 바꿔 쓴다. 이 플러그인의 CI는 Gradle 9.6.0까지만 검사한다.
  - 그래서 Gradle을 올릴 때마다 다시 확인해야 한다.
  - 깨지면 문자열 API로 돌아간다. 이때 모듈 스크립트의 `alias(conventions.…)`도 `id("loopers.…")`로 바꿔야 한다.
  - build-logic을 여러 프로젝트로 나누면 Isolated Projects에서 깨진다(플러그인 이슈 186).
- build-logic은 혼자 빌드되지 않는다(`-p build-logic`은 거절된다). 기본 컨벤션의 BOM 좌표를 카탈로그에서 읽는 것은, build-logic 안의 스크립트들이 컴파일 클래스패스를 함께 쓰기 때문이다. `SpringBootPlugin`을 직접 참조하면 다른 스크립트의 `alias`에 몰래 기대게 된다.
- 업스트림 템플릿에는 루트 `build.gradle.kts`가 있지만 이 저장소에는 없다. 이후 주차에 템플릿이 빌드 스크립트를 바꾸면 손으로 옮겨 와야 한다.
- 라이브러리 모듈의 jar 이름에서 Boot 플러그인이 붙이던 `-plain` 분류자가 빠진다.
- 정리하기 전까지는 쓰이지 않는 공통 의존성이 테스트가 없는 모듈에도 그대로 실리고, mockito-core는 BOM(5.23.0)보다 낮은 5.14.0에 고정되어 있다.

조사: [Gradle 버전 카탈로그와 컨벤션 플러그인 조사](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/f685ee017279c69934efb08f310986fc9d739664/docs/research/gradle-version-catalog-and-convention-plugins.md). typesafe-conventions 시험 결과는 그 문서의 6절에 있다.
