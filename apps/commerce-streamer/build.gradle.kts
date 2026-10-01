plugins {
    alias(conventions.plugins.loopers.spring.boot.application)
    alias(conventions.plugins.loopers.jpa)
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation(libs.springBootStarterWebmvc)
    implementation(libs.springBootStarterActuator)

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
