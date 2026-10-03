// 실행할 수 있는 Spring Boot 애플리케이션(apps 아래 모듈)의 컨벤션이다.
plugins {
    alias(conventions.plugins.loopers.kotlin.spring)
    alias(libs.plugins.springBoot)
}

// 앱은 bootJar 로 실행 jar 만 만든다. 일반 jar 는 끈다.
tasks.jar {
    enabled = false
}
