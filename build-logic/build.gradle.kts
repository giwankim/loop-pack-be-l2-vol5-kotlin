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
        // kotlin-dsl 과 typesafe-conventions 가 만든 build/generated-sources 는 통째로 뺀다(ADR 0009 의 조사 문서 6절).
        // 문자열 패턴은 소스 디렉터리 안의 상대 경로에 맞춰 보므로 파일 경로로 거른다.
        val generatedSources = layout.buildDirectory.dir("generated-sources")
        exclude { it.file.startsWith(generatedSources.get().asFile) }
    }
}
