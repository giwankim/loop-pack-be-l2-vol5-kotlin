plugins {
    `java-test-fixtures`
}

dependencies {
    api(libs.springBootStarterDataRedis)

    testFixturesImplementation(libs.testcontainers)
}
