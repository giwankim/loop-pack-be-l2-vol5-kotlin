// Gradle 에 내장된 Kotlin 으로 컴파일한다. 버전은 고정하지 않는다(ADR 0009).
// 컨벤션이 거는 플러그인의 의존성은 typesafe-conventions 가 더하므로 여기에 적지 않는다.
plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

// 컨벤션 스크립트도 모듈과 같은 ktlint 와 루트 .editorconfig 로 검사한다. pre-commit 훅이 :build-logic:ktlintCheck 를 함께 돌린다.
ktlint {
    version = libs.versions.ktlint
    filter {
        // build/generated-sources 는 통째로 뺀다. typesafe-conventions 의 출력만 빼면 kotlin-dsl 이 만든 접근자에서
        // 지적이 수천 건 나오고, kotlin-dsl 의 출력만 빼면 generateEntrypointForConventions 에 대한
        // 선언되지 않은 의존성으로 빌드가 실패한다(Gradle 9.7.1).
        // 문자열 패턴은 소스 디렉터리 안의 상대 경로에 맞춰 보므로 generated-sources 를 거르지 못한다. 그래서 파일 경로로 거른다.
        val generatedSources = layout.buildDirectory.dir("generated-sources").get().asFile
        exclude { it.file.startsWith(generatedSources) }
    }
}
