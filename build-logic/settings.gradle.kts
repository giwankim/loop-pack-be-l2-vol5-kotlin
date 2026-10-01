pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

// 루트 빌드의 gradle/libs.versions.toml 을 가져와 컨벤션 스크립트에 타입 안전한 libs 를 준다.
// plugins {} 의 alias(libs.plugins.x) 마다 플러그인 의존성을 이 빌드에 더하고, 루트 빌드에는 conventions 카탈로그를 만든다.
plugins {
    id("dev.panuszewski.typesafe-conventions") version "0.11.1"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // ktlint Gradle 플러그인은 플러그인 포털에만 있다.
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
