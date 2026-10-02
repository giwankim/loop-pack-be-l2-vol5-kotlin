dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "loopers-kotlin-spring-template"

include(
    ":apps:commerce-api",
    ":apps:commerce-streamer",
    ":apps:commerce-batch",
    ":modules:jpa",
    ":modules:redis",
    ":modules:kafka",
    ":supports:jackson",
    ":supports:logging",
    ":supports:monitoring",
)

// 컨벤션 플러그인을 담은 빌드다. pluginManagement 안에 넣으면, 이 settings 에 plugins {} 가 생기는 순간
// build-logic 이 먼저 평가되는 빌드가 되어 typesafe-conventions 가 실패한다.
includeBuild("build-logic")
