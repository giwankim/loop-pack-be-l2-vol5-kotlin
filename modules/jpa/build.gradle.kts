plugins {
    alias(libs.plugins.kotlin.jpa)
    `java-test-fixtures`
}

// BaseEntity 의 getter 가 final 이면 Hibernate 가 하위 엔티티의 프록시 팩토리를 만들지 못한다(HHH000305). commerce-api 와 같은 규칙.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    // jpa
    api(libs.springBootStarterDataJpa)
    // querydsl
    api(variantOf(libs.querydsl.jpa) { classifier("jakarta") })
    kapt(variantOf(libs.querydsl.apt) { classifier("jakarta") })
    // jdbc-mysql
    runtimeOnly(libs.mysql.connectorJ)

    testImplementation(libs.testcontainers.mysql)

    testFixturesImplementation(libs.springBootStarterDataJpa)
    testFixturesImplementation(libs.testcontainers.mysql)
}
