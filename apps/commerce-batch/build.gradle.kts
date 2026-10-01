plugins {
    alias(libs.plugins.kotlin.jpa)
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

    // querydsl
    kapt(variantOf(libs.querydsl.apt) { classifier("jakarta") })

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
