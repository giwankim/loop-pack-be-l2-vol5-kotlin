plugins {
    alias(libs.plugins.kotlin.jpa)
}

// plugin.spring(루트)은 Spring 애노테이션이 붙은 클래스만 연다. 엔티티도 열어야 Hibernate 가 LAZY 연관의 프록시를 만든다.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
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
    // validation: 루트는 runtimeOnly라 애노테이션을 쓰려면 컴파일 경로에도 있어야 한다
    implementation(libs.springBootStarterValidation)
    implementation(libs.springBootStarterActuator)
    implementation(libs.springdoc.openapiStarterWebmvcUi)

    // admin boundary: 통합 테스트 전용(src/test AdminSecurityConfig), 운영 코드에는 Spring Security 없음
    testImplementation(libs.springBootStarterSecurity)
    testImplementation(libs.springBootStarterSecurityTest)
    testImplementation(libs.springBootStarterWebmvcTest)
    testImplementation(libs.springBootStarterDataJpaTest)

    // querydsl
    kapt(variantOf(libs.querydsl.apt) { classifier("jakarta") })

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))

    // architecture test
    testImplementation(libs.archunit.junit6)
}
