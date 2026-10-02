plugins {
    alias(conventions.plugins.loopers.jpa)
    `java-test-fixtures`
}

dependencies {
    // jpa
    api(libs.springBootStarterDataJpa)
    // querydsl
    api(variantOf(libs.querydsl.jpa) { classifier("jakarta") })
    // jdbc-mysql
    runtimeOnly(libs.mysql.connectorJ)

    testImplementation(libs.testcontainers.mysql)

    testFixturesImplementation(libs.springBootStarterDataJpa)
    testFixturesImplementation(libs.springBootTest)
    testFixturesImplementation(libs.testcontainers.mysql)
}
