plugins {
    alias(conventions.plugins.loopers.kotlin.spring)
}

dependencies {
    // spring
    implementation(libs.springWeb)
    implementation(libs.springBootJackson)
    // jackson
    implementation(libs.jackson.kotlin)
}
