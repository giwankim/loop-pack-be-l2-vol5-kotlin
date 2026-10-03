plugins {
    alias(conventions.plugins.loopers.kotlin.spring)
}

dependencies {
    implementation(libs.springBootStarterActuator)
    implementation(libs.micrometer.registryPrometheus)
}
