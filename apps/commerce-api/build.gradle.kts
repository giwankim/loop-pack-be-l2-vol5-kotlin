plugins {
    alias(conventions.plugins.loopers.spring.boot.application)
    alias(conventions.plugins.loopers.jpa)
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation(libs.springBootStarterWebmvc)
    // validation: 기본 컨벤션은 runtimeOnly라 애노테이션을 쓰려면 컴파일 경로에도 있어야 한다
    implementation(libs.springBootStarterValidation)
    implementation(libs.springBootStarterActuator)
    implementation(libs.springdoc.openapiStarterWebmvcUi)

    // cache: 고객 상품 목록의 Redis 캐시(ADR 0020). 처음 쓰는 곳이라 여기서 들인다(ADR 0008)
    implementation(libs.springBootStarterCache)

    // PG client: 로컬 테스트 PG 어댑터의 HTTP client(ADR 0022). 처음 쓰는 곳이라 여기서 들인다(ADR 0008)
    // 스타터는 Boot가 미리 설정한 RestClient.Builder를 주고, request factory는 어댑터가 숨은 재전송을 끈 HttpClient 5로 바꾼다
    implementation(libs.springBootStarterRestclient)
    implementation(libs.httpclient5)

    // PG lookup resilience: 두 조회에만 재시도 한 번과 서킷 브레이커 pg-lookup을 애노테이션으로 씌운다(ADR 0030)
    // AspectJ는 지금 JPA를 거쳐서만 들어오는데, 없으면 애노테이션이 조용히 꺼지므로 직접 선언한다
    implementation(libs.resilience4j.springBoot4)
    implementation(libs.springBootStarterAspectj)

    // admin boundary: 통합 테스트 전용(src/test AdminSecurityConfig), 운영 코드에는 Spring Security 없음
    testImplementation(libs.springBootStarterSecurity)
    testImplementation(libs.springBootStarterSecurityTest)
    testImplementation(libs.springBootStarterWebmvcTest)
    testImplementation(libs.springBootStarterDataJpaTest)

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))

    // Only for KafkaTestUtils, which reads records back from the test broker. @EmbeddedKafka is not used (order-events design 8).
    testImplementation(libs.springBootStarterKafkaTest)

    // architecture test
    testImplementation(libs.archunit.junit6)

    // test PG: 테스트 JVM 안에 띄우는 WireMock(ADR 0022, wiremock-spring-boot 탐침 보고서)
    testImplementation(libs.wiremock.springBoot)
}

// wiremock-spring-boot 는 Jetty EE10 jar 를 12.1.6 으로 요구하는데, Boot BOM 은 Jetty core 와 EE11 만 jetty.version 으로 관리한다.
// 그대로 두면 EE10 만 12.1.6 으로 풀려 Boot 의 Jetty core 옆에 놓인다. EE10 BOM 을 Boot 의 jetty.version 과 같은 판으로 가져와 맞춘다.
// 판을 Boot 에서 읽지 않고 카탈로그에 적는다. importedProperties 를 읽는 순간 그때까지의 BOM 으로 관리 버전이 정해져, 뒤에 더한 import 가 무시된다.
// 대신 check 가 Boot 의 jetty.version 과 견준다. Boot 를 올려 Jetty 판이 바뀌면 빌드가 여기서 멈춘다. EE10 BOM 은 jetty.version 을 정의하지 않는다.
val jettyEe10Bom = libs.jettyEe10Bom.get()
dependencyManagement {
    imports {
        mavenBom("${jettyEe10Bom.group}:${jettyEe10Bom.name}:${jettyEe10Bom.version}")
    }
}
check(dependencyManagement.importedProperties["jetty.version"] == jettyEe10Bom.version) {
    "Boot 의 jetty.version(${dependencyManagement.importedProperties["jetty.version"]})이 카탈로그의 jettyEe10(${jettyEe10Bom.version})과 다르다."
}
