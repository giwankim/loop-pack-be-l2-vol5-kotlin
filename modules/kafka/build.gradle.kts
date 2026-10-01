plugins {
    `java-test-fixtures`
}

dependencies {
    api(libs.springBootStarterKafka)

    testImplementation(libs.springBootStarterKafkaTest)
    testImplementation(libs.testcontainers.kafka)

    testFixturesImplementation(libs.testcontainers.kafka)
}
