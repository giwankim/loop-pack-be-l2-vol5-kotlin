import org.gradle.api.Project.DEFAULT_VERSION
import org.springframework.boot.gradle.tasks.bundling.BootJar

/** --- configuration functions --- */
fun getGitHash(): String {
    return runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrElse { "init" }
}

/** --- project configurations --- */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.ktlint) apply false
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

allprojects {
    version = if (version == DEFAULT_VERSION) getGitHash() else version
}

subprojects {
    // 이 블록은 하위 프로젝트가 평가되기 전에 돌아서, 하위 프로젝트에는 아직 libs 확장이 없다. 루트의 카탈로그를 읽는다.
    val libs = rootProject.libs

    apply(plugin = libs.plugins.kotlin.jvm.get().pluginId)
    apply(plugin = libs.plugins.kotlin.kapt.get().pluginId)
    apply(plugin = libs.plugins.kotlin.spring.get().pluginId)
    apply(plugin = libs.plugins.springBoot.get().pluginId)
    apply(plugin = libs.plugins.springDependencyManagement.get().pluginId)
    apply(plugin = "jacoco")
    apply(plugin = libs.plugins.ktlint.get().pluginId)

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    dependencies {
        // Kotlin
        runtimeOnly("org.springframework.boot:spring-boot-starter-validation")
        implementation("org.jetbrains.kotlin:kotlin-reflect")
        // Spring
        implementation("org.springframework.boot:spring-boot-starter")
        // Serialize
        implementation("tools.jackson.module:jackson-module-kotlin")
        // Test
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        // testcontainers:mysql 이 jdbc 사용함
        testRuntimeOnly("com.mysql:mysql-connector-j")
        testImplementation("org.springframework.boot:spring-boot-starter-test")
        testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
        testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
        testImplementation("org.mockito:mockito-core:${project.properties["mockitoVersion"]}")
        testImplementation("org.mockito.kotlin:mockito-kotlin:${project.properties["mockitoKotlinVersion"]}")
        testImplementation("org.instancio:instancio-junit:${project.properties["instancioJUnitVersion"]}")
        testImplementation("org.instancio:instancio-kotlin:${project.properties["instancioJUnitVersion"]}")
        // Testcontainers
        testImplementation("org.springframework.boot:spring-boot-testcontainers")
        testImplementation("org.testcontainers:testcontainers")
        testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    }

    tasks.withType<Jar>().configureEach { enabled = true }
    tasks.withType<BootJar>().configureEach { enabled = false }

    configure(allprojects.filter { it.parent?.name.equals("apps") }) {
        tasks.withType<Jar>().configureEach { enabled = false }
        tasks.withType<BootJar>().configureEach { enabled = true }
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

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(properties["ktLintVersion"] as String)
    }
}

// module-container 는 task 를 실행하지 않도록 한다.
project("apps") { tasks.configureEach { enabled = false } }
project("modules") { tasks.configureEach { enabled = false } }
project("supports") { tasks.configureEach { enabled = false } }
