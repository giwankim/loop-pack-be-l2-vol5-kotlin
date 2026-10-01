// Gradle 에 내장된 Kotlin 으로 컴파일한다. 버전은 고정하지 않는다(ADR 0009).
// 컨벤션이 거는 플러그인의 의존성은 typesafe-conventions 가 더하므로 여기에 적지 않는다.
plugins {
    `kotlin-dsl`
}
