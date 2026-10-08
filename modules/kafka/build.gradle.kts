plugins {
    alias(conventions.plugins.loopers.kotlin.spring)
    `java-test-fixtures`
}

dependencies {
    api(libs.springBootStarterKafka)

    testImplementation(libs.springBootStarterKafkaTest)
    testImplementation(libs.testcontainers.kafka)

    testFixturesImplementation(libs.testcontainers.kafka)
}
