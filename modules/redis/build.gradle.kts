plugins {
    alias(conventions.plugins.loopers.kotlin.spring)
    `java-test-fixtures`
}

dependencies {
    api(libs.springBootStarterDataRedis)

    testFixturesImplementation(libs.springBootTest)
    testFixturesImplementation(libs.testcontainers)
}
