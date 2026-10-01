plugins {
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-data-redis")

    testFixturesImplementation("org.testcontainers:testcontainers")
}
