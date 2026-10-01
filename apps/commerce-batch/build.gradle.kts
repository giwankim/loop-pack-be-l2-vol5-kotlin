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

    // batch
    implementation(libs.springBootStarterBatchJdbc)
    testImplementation(libs.springBootStarterBatchJdbcTest)

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
