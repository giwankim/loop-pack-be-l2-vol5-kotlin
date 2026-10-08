plugins {
    alias(conventions.plugins.loopers.spring.boot.application)
    alias(conventions.plugins.loopers.jpa)
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation(libs.springBootStarterWebmvc)
    // validation: 기본 컨벤션은 runtimeOnly라 애노테이션을 쓰려면 컴파일 경로에도 있어야 한다
    implementation(libs.springBootStarterValidation)
    implementation(libs.springBootStarterActuator)
    implementation(libs.springdoc.openapiStarterWebmvcUi)

    // admin boundary: 통합 테스트 전용(src/test AdminSecurityConfig), 운영 코드에는 Spring Security 없음
    testImplementation(libs.springBootStarterSecurity)
    testImplementation(libs.springBootStarterSecurityTest)
    testImplementation(libs.springBootStarterWebmvcTest)
    testImplementation(libs.springBootStarterDataJpaTest)

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))

    // architecture test
    testImplementation(libs.archunit.junit6)
}
