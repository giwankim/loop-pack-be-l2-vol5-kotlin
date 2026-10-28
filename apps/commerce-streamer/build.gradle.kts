plugins {
    alias(conventions.plugins.loopers.spring.boot.application)
    alias(conventions.plugins.loopers.jpa)
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation(libs.springBootStarterWebmvc)
    implementation(libs.springBootStarterActuator)

    // validation: the convention adds it runtimeOnly, and the recording request's constraints need it on the compile path
    implementation(libs.springBootStarterValidation)

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:kafka")))

    // Only for KafkaTestUtils, which reads a group's committed offset. @EmbeddedKafka is not used (purchase activity
    // design 7).
    testImplementation(libs.springBootStarterKafkaTest)
}
