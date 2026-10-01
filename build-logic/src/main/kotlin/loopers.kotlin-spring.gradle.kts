import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// 모든 모듈의 기본 컨벤션이다. 다른 컨벤션은 이 컨벤션을 함께 건다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.springDependencyManagement)
    jacoco
    alias(libs.plugins.ktlint)
}

// jar 이름에 들어가는 버전은 git 짧은 해시다. 버전을 따로 정해 두었으면(-Pversion 등) 그 값을 쓴다.
version =
    if (version == Project.DEFAULT_VERSION) {
        runCatching {
            providers
                .exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText
                .get()
                .trim()
        }.getOrElse { "init" }
    } else {
        version
    }

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// 라이브러리 모듈에는 Boot 플러그인이 없다. Boot 플러그인이 앱에 해 주는 일 가운데 빌드 결과에 닿는 것은 여기서 모든 모듈에 한다
// (Boot 4.1 "Reacting to Other Plugins"): BOM 가져오기, Kotlin 의 -java-parameters, javac 의 -parameters 와 UTF-8 인코딩.
// BOM 좌표는 카탈로그에서 읽는다. SpringBootPlugin 을 참조하면, 다른 컨벤션이 Boot 플러그인을 build-logic 의
// 컴파일 클래스패스에 올려 준 덕에만 컴파일된다.
dependencyManagement {
    imports {
        val bom = libs.springBootDependencies.get()
        mavenBom("${bom.group}:${bom.name}:${bom.version}")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.javaParameters = true
}

// kapt 가 만든 QueryDSL Q 클래스는 javac 가 컴파일한다.
tasks.withType<JavaCompile>().configureEach {
    if ("-parameters" !in options.compilerArgs) {
        options.compilerArgs.add("-parameters")
    }
    if (options.encoding == null) {
        options.encoding = "UTF-8"
    }
}

dependencies {
    // Kotlin
    runtimeOnly(libs.springBootStarterValidation)
    implementation(libs.kotlin.reflect)
    // Spring
    implementation(libs.springBootStarter)
    // Serialize
    implementation(libs.jackson.kotlin)
    // Test
    testRuntimeOnly(libs.junit.platformLauncher)
    // testcontainers:mysql 이 jdbc 사용함
    testRuntimeOnly(libs.mysql.connectorJ)
    testImplementation(libs.springBootStarterTest)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.springmockk)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.instancio.junit)
    testImplementation(libs.instancio.kotlin)
    // Testcontainers
    testImplementation(libs.springBootTestcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junitJupiter)
}

tasks.test {
    maxParallelForks = 1
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
}

// jacoco 플러그인이 jacocoTestReport 에 test 태스크의 실행 데이터(build/jacoco/test.exec)를 지연 연결한다.
tasks.withType<JacocoReport>().configureEach {
    mustRunAfter("test")
    reports {
        xml.required = true
        csv.required = false
        html.required = false
    }
}

ktlint {
    version = libs.versions.ktlint
}
