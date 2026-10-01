dependencies {
    // spring
    implementation(libs.springBootStarterActuator)
    // monitoring
    implementation(libs.micrometer.registryPrometheus)
    implementation(libs.micrometer.tracingBridgeBrave)
    implementation(libs.springBootMicrometerTracingBrave)
    // Slack Appender
    implementation(libs.logbackSlackAppender)
    // Kotlin Logging
    api(libs.kotlinLogging)
}
