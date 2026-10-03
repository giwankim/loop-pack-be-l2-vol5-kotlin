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
        // Boot 플러그인은 앱에만 걸리므로, 그 플러그인이 켜 주는 -java-parameters 를 여기서 모든 모듈에 켠다.
        javaParameters = true
    }
}

// 라이브러리 모듈에는 Boot 플러그인이 없으므로 Boot BOM 을 여기서 가져온다. 좌표를 카탈로그에서 읽는 까닭은 ADR 0009 에 있다.
dependencyManagement {
    imports {
        val bom = libs.springBootDependencies.get()
        mavenBom("${bom.group}:${bom.name}:${bom.version}")
    }
}

ktlint {
    version = libs.versions.ktlint
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

// Boot 플러그인이 javac 에 거는 설정을 모든 모듈에 건다. kapt 가 만든 QueryDSL Q 클래스를 javac 가 컴파일한다.
tasks.withType<JavaCompile>().configureEach {
    if ("-parameters" !in options.compilerArgs) {
        options.compilerArgs.add("-parameters")
    }
    if (options.encoding == null) {
        options.encoding = "UTF-8"
    }
}

tasks.test {
    maxParallelForks = 1
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
}

// jacoco 플러그인이 jacocoTestReport 에 test 태스크의 실행 데이터(build/jacoco/test.exec)를 연결하고 test 뒤에 돌게 한다.
tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required = true
        csv.required = false
        html.required = false
    }
}
