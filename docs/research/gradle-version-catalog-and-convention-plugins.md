# Gradle version catalog and convention plugins: research notes

Researched 2026-10-01 against primary sources only, as input to a later decision interview and ADR. The sources are the Gradle 9.7.1 user guide, Javadoc and source, Gradle's GitHub issues, kotlinlang.org, the Spring Boot 4.1 docs and source, the dependency-management-plugin docs, source and issues, and plugin-portal and Maven Central POMs. This file states facts. It does not recommend anything.

Repo state at the time of writing: branch `refactor/architecture` at `5adb984`. The Gradle wrapper is 9.7.1 (`gradle/wrapper/gradle-wrapper.properties:3`). Boot is 4.1.1, Kotlin 2.3.21, io.spring.dependency-management 1.1.7, the ktlint plugin 14.2.0 and ktlint 1.8.0 (`gradle.properties:4-10`). The Java toolchain is 25 (`build.gradle.kts:25,49`).
- **Plugin versions.** `settings.gradle.kts:28-40` resolves them with `pluginManagement.resolutionStrategy.eachPlugin`, from values read with `val x: String by settings` (`:17-20`). `pluginManagement` also lists the repo.spring.io milestone and snapshot repositories before the plugin portal (`:22-26`).
- **Library versions.** Eight versions sit in `gradle.properties:12-19`. They are read as `project.properties["…"]` at `build.gradle.kts:69-73`, `apps/commerce-api/build.gradle.kts:25,41` and `supports/logging/build.gradle.kts:9,11`. The ktlint engine version is read at `build.gradle.kts:116`. Everything else is versionless and BOM-managed.
- **Root script.** `allprojects {}` (`build.gradle.kts:29-37`) sets group, version and `repositories { mavenCentral() }`. `subprojects {}` (`:39-118`) applies seven plugins, configures Kotlin, and adds 16 shared dependency declarations (`:57-77`). It also configures `Jar`/`BootJar`, tests, JaCoCo and ktlint. `:120-123` disables the tasks of the container projects `apps`, `modules` and `supports`.
- **Upstream.** The upstream template has the same layout: root `build.gradle.kts`, `settings.gradle.kts` and `gradle.properties`, with only `wrapper/` under `gradle/`. It has no catalog, no `buildSrc` and no `build-logic`. Its settings carry the same `eachPlugin` block, and its root script has the same `allprojects`/`subprojects` blocks with `io.spring.dependency-management`. Its last commit is `807c3f8` (2026-09-04) (`gh api repos/loopers-labs/loop-pack-be-l2-vol5-kotlin/contents/…`, read 2026-10-01).

Measurements (§5) ran on throwaway `git clone --local` copies in the session scratchpad, never in the working tree.

## Summary

- **Gradle's best practices (§1).** The 9.7.1 best-practice pages hold 45 practices. Against this repo, 15 are followed (one of them not measured), 4 partly, 17 not followed and 9 do not apply. Those not followed include version catalogs, convention plugins in a `build-logic` build, repositories in settings, avoiding `afterEvaluate`, avoiding empty container projects, and the build and configuration caches. Gradle 9.8.0 adds 3 practices, none of which applies here.
- **Catalogs centralize declared versions; they do not resolve versions (§2.4).** Gradle: "version catalogs only influence declared versions, not resolved versions", and "a good approach is to use a version catalog to define dependency versions alongside a platform to enforce them" ([bp-deps], [catalog-platform]). A catalog cannot import a BOM: `from()` accepts only a catalog ([vcb-javadoc]). A versionless entry is written `x = { module = "g:a" }` ([vc]).
- **Two TOML limits matter here (§2.6, §2.5).** Catalogs "do not support classifiers". The four `querydsl-*::jakarta` declarations would need `variantOf(libs.x) { classifier("jakarta") }` at the use site ([vc] `#sec:classifiers_artifact_types_and_capabilities`). Settings and precompiled-script `plugins {}` blocks cannot use catalog plugin aliases ([vc]).
- **Gradle prefers an included `build-logic` build over `buildSrc` (§3.1).** "Any change in buildSrc causes the entire build to become out-of-date" ([bp-struct]). Precompiled script plugins apply third-party plugins by putting the plugin artifact on the build-logic build's `implementation` classpath. Their `plugins {}` block takes no version ([precompiled]). §3.3 lists verified coordinates for every plugin. The ktlint plugin is published only on the Gradle Plugin Portal, not on Maven Central.
- **No type-safe `libs` accessors in precompiled script plugins (§3.5).** [gradle/gradle#15383][gh-15383] has been open since 2020-12-01, with no milestone. The documented route is `the<VersionCatalogsExtension>().named("libs").findLibrary(…)`. The community workaround is `implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))`, which Gradle's docs do not mention. Both worked on 9.7.1 in a throwaway trial (§5.6).
- **kotlin-dsl on Gradle 9.7.1 embeds Kotlin 2.4.0 (§3.6).** The project uses Kotlin 2.3.21. Gradle prints "Unsupported Kotlin plugin version" only when another Kotlin plugin version is applied in the same project as `kotlin-dsl`. In the trial, putting KGP 2.3.21 on build-logic's `implementation` classpath printed no warning.
- **Spring Boot documents both approaches without a preference (§4.1).** The dependency-management plugin "offers property-based customization of managed versions", while Gradle's native bom support "will likely result in faster builds" ([boot-deps]). The plugin's maintainer recommended the native platform in 2024, pending a plugin 1.2 release ([dmp-369]). The plugin's docs list Gradle "6.x (6.8 or later), 7.x, or 8.x", not 9 ([dmp]). Initializr still generates the plugin for Boot 4.1.1.
- **The mockito downgrade is documented behaviour (§4.3).** By default (`overriddenByDependencies = true`), a direct `mockito-core:5.14.0` becomes the managed version for that configuration's whole graph, through `eachDependency { useVersion }` ([dmp]). The maintainer calls this "an intentional feature" ([dmp-406]).
- **Measured: swapping the plugin for `platform(SpringBootPlugin.BOM_COORDINATES)` moves 14 coordinates upward and nothing downward (§5.2).** Over the four requested classpaths there are 63 (module, configuration, coordinate) differences:
  - `mockito-core` 5.14.0 → 5.23.0 and `slf4j-api` 2.0.18 → 2.0.19, in every module's two test classpaths;
  - five Jackson 2 artifacts 2.21.x → 2.22.x, in all four classpaths of commerce-api;
  - seven JUnit artifacts 6.0.3 → 6.1.3, in commerce-api's `testRuntimeClasspath`.

  No library appears or disappears, and `kotlin-stdlib` stays 2.3.21. `compileTestKotlin` and `ktlintCheck` pass, and the suite runs 429/429 green in both setups.
- **Surprise: the dependency-management plugin also rewrites build-tool classpaths (§5.3).** In the baseline it raises the Kotlin compiler's `kotlin-reflect` from the declared 1.6.10 to 2.3.21. It also raises ktlint's embedded `kotlin-compiler-embeddable` from 2.2.21 to 2.3.21, in all 9 modules. With the platform, both return to their declared versions ([dmp-407] tracks the first).
- **Resolution time (§5.4).** With a warm daemon, resolving every resolvable configuration of all projects took about 1.5 s with the plugin and about 0.3 s with the platform, on top of about 0.82 s of configuration (5 runs each).
- **The root block's 16 shared declarations (§5.5).** 10 have no importing source file in any module: `jackson-module-kotlin`, `kotlin-test-junit5`, `springmockk`, `mockito-core`, `mockito-kotlin`, `instancio-junit`, `instancio-kotlin`, `spring-boot-testcontainers`, `testcontainers` and `testcontainers-junit-jupiter`. `instancio-junit` is still loaded at runtime through `META-INF/services`. Three are runtime-only by design. Six of the nine modules have no test sources, but all receive the 12 test-scope declarations.
- **Gradle 10 deprecations (§5.6).** A configuration run prints 15 Gradle 10 deprecation warnings. 14 of them are version lookups (`by settings`, `project.properties[…]`). The fifteenth is `val projectGroup: String by project`.
- **Configuration cache and Isolated Projects (§5.6).** The configuration cache already stores and reuses entries for this build with no problems. Isolated Projects stops at `build.gradle.kts:30`.

## 1. Gradle best practices (9.7.1) applied to this repo

**Pages read** ([bp]): the [index][bp-index] and seven practice pages, [General][bp-general], [Structuring Builds][bp-struct], [Dependencies][bp-deps], [Tasks][bp-tasks], [Performance][bp-perf], [Security][bp-sec] and [Testing][bp-test]. The section has no Kotlin DSL page.
- **Count.** The index table lists 44 practices. The pages hold 45, because "Don't Assume your Plugin is Applied after Another" is on the General page but missing from the index, in both 9.7.1 and 9.8.0.
- **Titles.** Seven index titles differ slightly from the page headings. The headings are used below.
- **Status words.** "Follows", "Partly", "Does not follow", "N/A". Evidence is `file:line`.

### 1.1 General

| # | Practice (anchor on [bp-general]) | Gradle's rationale | Repo | Evidence |
| --- | --- | --- | --- | --- |
| 1 | Use Kotlin DSL `#use_kotlin_dsl` | Strict typing gives IDE completion and navigation: "IDEs provide better auto-completion and navigation with the Kotlin DSL." | Follows | All 11 scripts are `.kts`. |
| 2 | Use the Latest Minor Version of Gradle `#use_latest_minor_versions` | "Only the latest minor version of the current and previous major release is actively supported." Each minor's deprecations prepare for the next major. | Does not follow | The wrapper is 9.7.1 (`gradle-wrapper.properties:3`). 9.8.0 was released 2026-09-24 ([gradle-versions]). 9.7.1 is the wrapper Initializr generates for Boot 4.1.1 ([init-zip]), which ADR 0008 follows (`docs/adr/0008-upgrade-to-spring-boot-4-1-ahead-of-the-template.md:14`). The 15 Gradle 10 deprecations are listed in §5.6. |
| 3 | Apply Plugins Using the plugins Block `#use_the_plugins_block` | The block is "constrained to be idempotent (produce the same result every time) and side effect-free", so Gradle can optimize plugin loading. | Partly | The root declares plugins in `plugins {}` (`build.gradle.kts:14-21`), then applies them with `apply(plugin = …)` (`:40-46`), which is the page's "Don't Do This" form. Module scripts use `plugins {}` (`apps/*/build.gradle.kts:1-3`, `modules/{jpa,redis,kafka}/build.gradle.kts:1-4`). |
| 4 | Don't Assume your Plugin is Applied after Another `#dont_assume_plugin_order` | Plugin order is deterministic but opaque. "Don't rely on blocks like allprojects {}, subprojects {}, or afterEvaluate {} that are highly dependent on project structure and file layout." | Does not follow | `allprojects` (`build.gradle.kts:29`), `subprojects` (`:39`), `afterEvaluate` (`:104`). The `allOpen {}` blocks (`apps/commerce-api/build.gradle.kts:6-10`, `modules/jpa/build.gradle.kts:7-11`) rely on the root having applied `plugin.spring` (`build.gradle.kts:42`). `configure(allprojects.filter { it.parent?.name.equals("apps") })` (`:83-86`) keys on a directory name and runs once per subproject. |
| 5 | Do Not Use Internal APIs `#do_not_use_internal_apis` | Internal APIs are "subject to unannounced breaking changes during any new Gradle release, even during minor releases." | Follows | The imports at `build.gradle.kts:1-2` are public. |
| 6 | Set Build Flags in gradle.properties `#use_the_gradle_properties_file` | Command-line flags are "prone to being forgotten or inconsistently applied across environments." | Follows | No `org.gradle.*` flag is passed anywhere. `.githooks/pre-commit:4` runs a plain `gradlew ktlintCheck`. There is no CI workflow (`.github/` holds only `auto_assign.yml` and `pull_request_template.md`). |
| 7 | Name Your Root Project `#name_your_root_project` | Otherwise the name and task paths depend on the checkout directory. | Follows | `settings.gradle.kts:1`. |
| 8 | Do not use gradle.properties in subprojects `#do_not_use_gradle_properties_in_subprojects` | "support for subproject properties is inconsistent". | Follows | Only the root `gradle.properties` exists. |
| 9 | Avoid afterEvaluate `#avoid_after_evaluate` | Fragile ordering. It defeats configuration avoidance, and "afterEvaluate callbacks capture mutable project state that cannot be serialized reliably." | Does not follow | `build.gradle.kts:104-112`, inside `tasks.withType<JacocoReport>`. It rewraps `classDirectories` with no excludes. |
| 10 | Consider use of @Incubating APIs carefully `#consider_use_of_incubating_apis_carefully` | "An Incubating state means that breaking changes can happen in non-major releases." | Follows | No incubating API is used. The page names `dependencyResolutionManagement.repositories` as incubating but widely adopted. The repo does not use it. |

### 1.2 Structuring builds

| # | Practice (anchor on [bp-struct]) | Gradle's rationale | Repo | Evidence |
| --- | --- | --- | --- | --- |
| 11 | Modularize Your Builds `#modularize_builds` | "If all your sources reside in a single project, Gradle can't avoid recompilation and won't be able to run tasks in parallel." | Follows | 9 source projects (`settings.gradle.kts:3-13`). |
| 12 | Do Not Put Source Files in the Root Project `#no_source_in_root` | The root is the entry point. "Be careful not to apply plugins unnecessarily in the root project". | Partly | There is no root `src/`. But the root applies `kotlin("jvm")`, `kotlin("kapt")` and dependency-management without `apply false` (`build.gradle.kts:15-16,19`), and sets a toolchain on itself (`:23-27`). |
| 13 | Favor build-logic Composite Builds for Build Logic `#favor_composite_builds` | "The preferred location for build logic is an included build (typically named build-logic), not in buildSrc." | Does not follow | There is no `build-logic` and no `buildSrc`. All shared logic is in `build.gradle.kts:29-123`. See §3.1. |
| 14 | Avoid Unintentionally Creating Empty Projects `#avoid_empty_projects` | Nested include paths create an empty project per segment. "If you use allprojects { … } or subprojects { … }, plugins and configuration blocks will apply to every project, including the empty ones." | Does not follow | `settings.gradle.kts:4-12` creates the empty `:apps`, `:modules` and `:supports` projects. `subprojects {}` applies seven plugins to them, and `build.gradle.kts:120-123` then disables their tasks. The page's fix is flat include names plus `projectDir`. |
| 15 | Use Convention Plugins for Common Build Logic `#use_convention_plugins` | One home for shared logic: "Instead of duplicating configuration across multiple build scripts, you can easily move common logic into a reusable convention plugins." | Does not follow | Shared logic is in `subprojects {}` (`build.gradle.kts:39-118`). Repeated blocks: `plugin.jpa` in all three apps (`apps/*/build.gradle.kts:1-3`); `allOpen` twice; `kapt("com.querydsl:querydsl-apt::jakarta")` four times (`apps/commerce-api/build.gradle.kts:34`, `apps/commerce-batch/build.gradle.kts:18`, `apps/commerce-streamer/build.gradle.kts:19`, `modules/jpa/build.gradle.kts:18`); the `testFixtures` pair in all three apps. |

### 1.3 Dependencies

| # | Practice (anchor on [bp-deps]) | Gradle's rationale | Repo | Evidence |
| --- | --- | --- | --- | --- |
| 16 | Use Version Catalogs to Centralize Dependency Versions `#use_version_catalogs` | "Instead of changing dozens of build.gradle(.kts) files, you update the version in one place." | Does not follow | There is no `gradle/libs.versions.toml`. Versions are in `gradle.properties:4-19`, read at the sites listed in the repo-state paragraph. |
| 17 | Name Version Catalog Entries Appropriately `#name_version_catalog_entries` | "Consistent and descriptive names in your version catalog enhance readability and maintainability". | N/A | There is no catalog. §2.2 and the repo inventory apply the rules. |
| 18 | Set up your Dependency Repositories in the Settings file `#set_up_repositories_in_settings` | "Repositories are not part of the project definition; they are part of global build logic". | Partly | Plugin repositories are in settings (`settings.gradle.kts:22-26`). Dependency repositories are set per project by `allprojects { repositories { mavenCentral() } }` (`build.gradle.kts:34-36`). |
| 19 | Don't Explicitly Depend on the Kotlin Standard Library `#dont_depend_on_kotlin_stdlib` | KGP adds it automatically, at its own version. | Follows | No `kotlin-stdlib` declaration. `kotlin-reflect` (`build.gradle.kts:58`) is a different artifact. |
| 20 | Avoid Redundant Dependency Declarations `#avoid_duplicate_dependencies` | "Declaring the same dependency in multiple configurations (e.g., compileOnly and implementation) can result in hard-to-diagnose classpath issues." | Does not follow | `jackson-module-kotlin` in every module (`build.gradle.kts:62`) and again in `supports/jackson/build.gradle.kts:6`. `spring-boot-starter-validation` as `runtimeOnly` (`build.gradle.kts:57`) and `implementation` (`apps/commerce-api/build.gradle.kts:23`); its comment at `:22` explains this one. `mysql-connector-j` as `testRuntimeOnly` everywhere (`build.gradle.kts:66`) and `runtimeOnly` in `modules/jpa/build.gradle.kts:20`. |
| 21 | Declare Dependencies using a single GAV (group:artifact:version) String `#single-gav-string` | "The named argument notation has been deprecated and will no longer be supported starting in Gradle 10." | Follows | Every declaration is a single string. |
| 22 | Use Content Filtering with multiple Repositories `#use_content_filtering` | Performance, reliability and "Security, by avoiding asking potentially every repository for every dependency". | Does not follow (plugin repositories) | `settings.gradle.kts:23-25`: two repo.spring.io repositories are searched before the plugin portal, with no `content {}` filter. Every plugin in use is a release on the portal (§3.3). Dependency resolution has one repository, so the practice does not apply there. |
| 23 | Apply Exclusions Narrowly `#apply_exclusions_narrowly` | "Exclusions can negatively affect dependency resolution performance." | N/A | No `exclude` in the repo. The dependency-management plugin applies POM exclusions itself (§4.4). |
| 24 | Always Declare Attributes on Consumable and Resolvable Configurations `#use_attributes_on_configurations` | "Configuration names should be treated as internal implementation details." | N/A | No custom configurations. |

### 1.4 Tasks

| # | Practice (anchor on [bp-tasks]) | Gradle's rationale | Repo | Evidence |
| --- | --- | --- | --- | --- |
| 25 | Avoid DependsOn `#avoid_depends_on` | "dependsOn forces Gradle to assume that every file produced by a prerequisite task is needed by this task." | Follows | No `dependsOn`. `build.gradle.kts:97` uses `mustRunAfter("test")`. |
| 26 | Favor @CacheableTask and @DisableCachingByDefault over cacheIf(Spec) and doNotCacheIf(String, Spec) `#use_cacheability_annotations` | Cacheability is documented per type, not per instance. | N/A | No custom task types. |
| 27 | Do not call get() on a Provider outside a Task action `#avoid_provider_get_outside_task_action` | "The value of the provider becomes an input to configuration, causing potential configuration cache misses." | Does not follow | `build.gradle.kts:9` calls `.standardOutput.asText.get()` at configuration time, through `getGitHash()` from `allprojects` (`:32`). The configuration-cache docs say such a value "will become a build configuration input" ([cc-req]). |
| 28 | Group and Describe custom Tasks `#group_describe_tasks` | "Tasks with no group are hidden from the Tasks Report unless --all is specified." | N/A | No tasks are registered. |
| 29 | Avoid using eager APIs on File Collections `#avoid_eager_file_collection_apis` | "Converting a Configuration to a Set<File> also discards any implicit task dependencies it carries." | Does not follow | `build.gradle.kts:107` calls `classDirectories.files` inside `afterEvaluate`. |
| 30 | Don't resolve Configurations before Task Execution `#dont_resolve_configurations_before_task_execution` | It "can slow down the build, even when running unrelated tasks (e.g., help)". | Follows (in scripts) | No script resolves a configuration. Plugin internals were not checked. |
| 31 | Use @PathSensitivity.NONE for file inputs and @PathSensitivity.RELATIVE for directories `#default_path_sensitivities` | "If no @PathSensitive annotation is provided, PathSensitivity.ABSOLUTE is the default." | N/A | No custom task types. |
| 32 | Use unique output files and directories `#use_unique_output_files_and_directories` | "Overlapping output files or directories cause tasks to rerun unnecessarily". | N/A | No custom outputs. |
| 33 | Don't hardcode Task names unless they are documented as Public API `#dont_hardcode_task_names` | "Most other task names in Gradle and third-party plugins are internal implementation details." | Follows | Tasks are configured by type (`build.gradle.kts:80-81,84-85,96`). `"test"` (`:88,97`) is the Java plugin's public task. |
| 34 | Don't access a Project instance during Task Execution `#dont_access_project_instance_inside_task` | "Accessing the Project instance during task execution is not compatible with the Configuration Cache". | Follows | No `doFirst`/`doLast` in the scripts. |
| 35 | Wire lazy task outputs using map and flatMap `#map_versus_flatmap` | They "preserve the task dependency chain so Gradle knows which tasks must run first." | Does not follow | `build.gradle.kts:98` feeds the test task's `.exec` file to `JacocoReport` through a path glob. That carries no task dependency, and `:97` only orders the tasks. |

### 1.5 Performance, security and testing

| # | Practice | Gradle's rationale | Repo | Evidence |
| --- | --- | --- | --- | --- |
| 36 | Prefer the -bin Gradle Distribution ([bp-perf] `#prefer_bin_distribution`) | It "limits the number of artifacts you need to trust." | Follows | `gradle-wrapper.properties:3` (`-bin.zip`). |
| 37 | Use UTF-8 File Encoding ([bp-perf] `#use_utf8_encoding`) | "differences in file encoding between environments can cause unexpected cache misses." | Does not follow | No `org.gradle.jvmargs` in `gradle.properties`. |
| 38 | Use the Build Cache ([bp-perf] `#use_build_cache`) | "If the inputs are the same, the outputs will be too". | Does not follow | No `org.gradle.caching=true`. |
| 39 | Use the Configuration Cache ([bp-perf] `#use_configuration_cache`) | "The Configuration Cache is the preferred way to execute Gradle builds, but it is not enabled by default." | Does not follow (not enabled) | No `org.gradle.configuration-cache` in `gradle.properties`. Every run prints "Consider enabling configuration cache". The build is compatible as measured (§5.6). |
| 40 | Avoid Expensive Computations in Configuration Phase ([bp-perf] `#avoid_computations_in_configuration_phase`) | File or network I/O at configuration time runs "even when they might be unnecessary". | Does not follow | `build.gradle.kts:5-11` runs `git rev-parse` at configuration time, called from `allprojects` (`:32`). |
| 41 | Validate the Gradle Distribution SHA-256 Checksum ([bp-sec] `#validate_gradle_checksum`) | Protects "your build from corruption or tampering." | Does not follow | No `distributionSha256Sum` in `gradle-wrapper.properties`. |
| 42 | Validate the Gradle Wrapper on every Upgrade ([bp-sec] `#validate_wrapper_checksum`) | "Running an unverified Wrapper risks executing untrusted code". | Partly | Nothing enforces it; there is no CI. The local `gradle-wrapper.jar` matches the official 9.7.1 checksum. |
| 43 | Do not Run ./gradlew on Untrusted Projects ([bp-sec] `#run_gradle_on_external_projects`) | Like running "any other untrusted script from the internet." | N/A | Advice for people cloning foreign repos. |
| 44 | Build Output Should Be Byte-for-Byte Reproducible ([bp-sec] `#builds_should_be_reproducible`) | "Since Gradle 9.0.0, archive artifacts are set to be reproducible by default." | Follows (not measured) | No overrides of the archive defaults. The version is the git short hash (`build.gradle.kts:5-11,32`). |
| 45 | Test your custom Task and Plugins with TestKit ([bp-test] `#test_custom_types_with_testkit`) | "A mature build should include functional tests for its custom types". | N/A today | No custom types or plugins yet. Convention plugins in a `build-logic` build would be custom plugins. |

Tally: 15 Follows (one not measured), 4 Partly, 17 Does not follow, 9 N/A.

### 1.6 Gradle 9.8.0 differences

All nine pages were compared as text against 9.7.1. Nothing was removed or renamed. Three practices were added, each marked "Added in 9.8.0" in the index:
- **Obtain Loggers via Logging.getLogger(Class) outside of Tasks** ([bp98-general]). In compiled build logic, including convention plugins, use a class-named logger, not `project.logger`. Using `project.logger` at execution time breaks the configuration cache. N/A today: the scripts do not log.
- **Favor collection property types over a Property holding a collection** ([bp98-tasks]). Use `ListProperty`, `SetProperty` and `MapProperty`. N/A today: no custom types.
- **Build your Published Artifacts Securely** ([bp98-sec]). "Do not use incremental, local, or cached builds when publishing." N/A: nothing is published.

The other differences are an example Kotlin version (2.4.0 → 2.4.10) and two reworded sentences on the Testing page.

## 2. Version catalogs

### 2.1 TOML format

- **Sections.** "The version catalog TOML file has four sections": `[versions]`, `[libraries]`, `[bundles]` and `[plugins]` ([vc] `#sec::toml-dependencies-format`; the anchor really has two colons). The parser also accepts `[metadata]` (`TomlCatalogFileParser.java` at `v9.7.1`).
- **Library forms** ([vc] `#sec:libraries`):
  - a string `"g:a:v"`;
  - `x.module = "g:a"`;
  - `{ module = "g:a", version = "…" }`;
  - `{ group = "…", name = "…", version = … }`;
  - `version.ref = "<key>"`, which points into `[versions]`.
- **Rich versions.** A plain string "is interpreted as a required version". A rich version may use `require`, `strictly`, `prefer`, `reject` and `rejectAll`, for example `{ strictly = "[1.0, 2.0[", prefer = "1.2" }` ([vc] `#sec:common-version-numbers`). The `!!` shorthand works in strings, as in `"1.0!!"` ([vc-problems] `#invalid_version_notation`).
- **Bundles** "group multiple library aliases, so they can be referenced together", as in `implementation(libs.bundles.groovy)` ([vc] `#sec:dependency-bundles`).
- **What the TOML cannot express**: "classifier attributes … exclude rules … capabilities requirements" ([vc] `#sec:toml-limitations`).
- **Plugin rich versions.** Gradle issue 39350, opened 2026-09-30 with milestone 9.9.0 RC1, proposes to "make non-required versions for plugins in a version catalog an error" ([gh-39350]).

### 2.2 Alias naming and accessors

- **Accessor derivation.** "Aliases in a version catalog consist of identifiers separated by a dash (-) or underscore (_). Type-safe accessors are generated for each alias, normalized to dot notation", so `ktor-client-core` becomes `libs.ktor.client.core` ([vc] `#sec:mapping-aliases-to-accessors`). Dots in TOML keys create nested tables and cannot be part of an alias.
- **Alias format.** An alias must match `[a-z]([a-zA-Z0-9_.\-])+` ([vc-problems] `#invalid_alias_notation`). camelCase avoids sub-groups: `groovyCore` gives `libs.groovyCore` ([vc] `#sec:avoiding_subgroup_accessors`).
- **Clashes.** Two aliases that map to the same accessor (for example `someAlias` and `some-alias`) are an error ([vc-problems] `#accessor_name_clash`).
- **Reserved words.** "Certain keywords, like extensions, class, and convention, are reserved and cannot be used as aliases. Additionally, bundles, versions, and plugins cannot be the first subgroup in a dependency alias" ([vc] `#sec:reserved_keywords`).
- **Prefix aliases.** A leaf alias and a group of the same name can coexist, for example `testcontainers` with `testcontainers-mysql`, or `junit-jupiter` with `junit-jupiter-launcher`.
  - The docs show this for versions, via `libs.versions.jackson.asProvider()` ([vc] `#sec:avoiding_subgroup_accessors`).
  - They show it for libraries only by example: `libs.junit.jupiter` next to `libs.junit.jupiter.launcher` ([catalog-platform] `#sec:catalog-platform`).
  - In the generator source, such a group class implements the provider interface, so `implementation(libs.testcontainers)` works directly. Only version accessors need `.asProvider()` where a `Provider<String>` is required.
- **Best practice "Name Version Catalog Entries Appropriately"** ([bp-deps] `#name_version_catalog_entries`). "Aliases are typically made up of 1 to 3 segments." The rules:
  1. "Use dashes to separate segments".
  2. "Derive the first segment from the project group … Do not include the top level domain in the segment (com, org, net, dev)".
  3. "Derive the second segment from the artifact ID".
  4. "Avoid generic terms in the segments … (core, java, gradle, module, sdk)".
  5. "Omit redundant segments", for example `ktor-client-core`, not `ktor-ktor-client-core`.
  6. "Convert internal dashes to camelCase", with the example "spring-boot-starter-web becomes springBootStarterWeb".
  7. "Suffix plugin libraries with -plugin" when a plugin is referenced as a library, for example `org.owasp:dependency-check-gradle` becomes `dependency-check-plugin`.
- **The rules allow more than one key.** The page itself says `org.apache.commons:commons-lang3` "could be represented as commonsLang3, apache-commonsLang3, or commons-lang3". Its own example mixes forms (`jackson-databind`, `jackson-dataformatCsv`). It gives no rule for `[versions]` or `[plugins]` keys, but its examples use camelCase (`commonsLang`) and a short id word (`versions` for `com.github.ben-manes.versions`).

### 2.3 Versionless entries for a BOM

- **Documented form.** `my-lib-no-version.module = "com.mycompany:mylib"` ([vc] `#sec:libraries`). The catalog-plus-platform page uses `guava = { module = "com.google.guava:guava"}`, with versions coming from a `platform(...)` dependency ([catalog-platform] `#sec:catalog-platform`).
- **Two-part strings are rejected.** A two-part string such as `"g:a"` is rejected; the parser error says "To declare without a version, use '<alias>.module' instead". Gradle closed the request to integrate BOMs into catalogs as not planned ([gh-17117]).
- **Gradle's general advice.** "For larger projects, it's advisable to declare dependencies without versions and manage versions using platforms" ([dep-versions] `#sec:declaring-without-version`).

### 2.4 Catalogs and platforms

- **Catalogs request versions; they do not enforce them.** "Version catalogs declare requested versions but do not enforce them. Gradle may still select different versions due to dependency graph conflicts or constraints applied through platforms or other dependency management APIs" ([vc], introduction).
- **Catalogs are invisible to resolution.** "Version catalogs do not directly affect dependency resolution" ([catalog-platform] `#sec:version-catalogs`). By contrast, "A platform is a module in the dependency graph that enforces or aligns versions of dependencies (including transitive dependencies)" ([catalog-platform] `#sec:platforms`).
- **Using both.** "a good approach is to use a version catalog to define dependency versions alongside a platform to enforce them" ([catalog-platform] `#sec:catalog-platform`). The best-practices page: "version catalogs only influence declared versions, not resolved versions … To influence resolved versions, check out platforms" ([bp-deps] `#use_version_catalogs`). "You cannot declare constraints in version catalogs" ([dep-constraints]).
- **No BOM import into a catalog.** `VersionCatalogBuilder.from` reads "a component published using the version-catalog plugin or a local TOML file. This function can be called only once" ([vcb-javadoc]). The source rejects non-`.toml` files. No 9.7.1 page says outright "a BOM cannot be imported as a catalog".
- **A BOM entry in the catalog.** `DependencyHandler` has `platform(Provider<MinimalExternalModuleDependency>)` (since 6.8) and `platform(ProviderConvertible<…>)` (since 7.3), and the same pair for `enforcedPlatform` ([dh-javadoc]). The 9.7.1 user guide has no `platform(libs.x)` example. Gradle declined to merge catalogs and platforms: "Platforms provide constraints for dependency versions, but may not necessarily include dependencies themselves" ([gh-26048]).
- **BOM semantics in Gradle.** "Gradle treats all entries in the block of a BOM similar to Adding Constraints On Dependencies" ([platforms] `#sec:bom-import`). An `enforcedPlatform` "can be used to override any versions found in the dependency graph, but should be used with caution as it is effectively transitive and exports forced versions to all consumers" ([platforms] `#sec:enforced-platform`). On a declared dependency, "strictly can downgrade a version" ([dep-versions] `#sec:strict-version`). "Dependency constraints are transitive" ([dep-constraints]).

### 2.5 `[plugins]`, `alias()` and `eachPlugin`

- **`alias()`.** A catalog plugin is applied with `alias(libs.plugins.x)` in `plugins {}` ([vc] `#sec:plugins-ver`). It returns a `PluginDependencySpec`, so `apply false` chains, and "The resulting dependency spec can be refined with a version overriding what the version catalog provides" ([pds-javadoc]). Since Gradle 9.0, referencing libraries or bundles in `plugins {}` "will now result in a build failure" ([upgrading-major9]).
- **Where catalog plugins cannot be used.** "You cannot use a plugin declared in a version catalog in your settings file or settings plugin" ([vc] `#sec:plugins-ver`). "the plugins block in the precompiled script plugin cannot access the version catalog" ([vc] `#sec:buildsrc-version-catalog`).
- **Root `apply false` plus a subproject `alias()`.** The documented pattern declares the plugin with a version and `apply false` at the root, then applies it without a version in subprojects ([plugins-int] `#sec:root_plugins_dsl`, `#sec:subprojects_plugins_dsl`). A subproject `alias()` carries the version again. That works when the versions match, since Gradle 7.4 ([gh-18236], closed by PR 19015). It fails only when the plugin is already on the classpath with an unknown or different version.
- **The central-version alternative to `eachPlugin`.** "A plugins{} block inside pluginManagement{} allows all plugin versions for the build to be defined in a single location". Unlike the build-script block, it "does not have the same constrained syntax", which "allows plugin versions to be taken from gradle.properties" ([plugins-int] `#sec:plugin_version_management`). In a settings script, the API "sets the default version of a plugin" ([pds-javadoc]).
- **`eachPlugin` itself.** "Plugin resolution rules allow you to modify plugin requests made in plugins{} blocks, e.g., changing the requested version" ([plugins-int] `#sec:plugin_resolution_rules`). It is not deprecated. Gradle issue 20545, "resolutionStrategy in pluginManagement is not working for plugins with multivariants", is open, and Kotlin's docs link to it ([gh-20545]).

### 2.6 Using catalog entries

- **In build scripts.** `libs.<alias>`, `libs.plugins.<alias>`, `libs.versions.<alias>` and `libs.bundles.<alias>`. "A version catalog accessor such as libs.groovy.core returns a Provider<MinimalExternalModuleDependency>" ([vc] `#sec:accessing-catalog`, `#sec:using_catalog_entries_in_gradle_apis`).
- **The default file.** "Gradle automatically imports a catalog in the gradle directory named libs.versions.toml", and `from()` "may only be called once per catalog" ([vc] `#sec:importing-catalog-from-file`, `#sec:sharing_versions_across_multiple_catalogs`). So the main build needs no `versionCatalogs {}` block for that file. "Most builds should use a single libs.versions.toml file" ([vc] `#sec:working-multiple-version-catalogs`).
- **String-based API.** `extensions.getByType(VersionCatalogsExtension::class.java).named("libs")`, then `findLibrary`, `findVersion` (returns `Optional<VersionConstraint>`), `findPlugin` and `findBundle`. "there is no guarantee that the requested aliases exist, so you must check existence on the returned optional values". Aliases passed in are normalized (`-`, `_` and `.` become `.`) ([vc] `#sec:buildsrc-version-catalog`; [vcat-javadoc]; [vcats-javadoc]).
- **Classifiers.** "They do not support classifiers … directly in the TOML file. When you need a specific classifier … use variantOf() at the dependency declaration site", for example `implementation(variantOf(libs.my.lib) { classifier("test-fixtures") })` ([vc] `#sec:classifiers_artifact_types_and_capabilities`). The repo uses the `jakarta` classifier for `querydsl-apt` (4 sites) and `querydsl-jpa` (1 site), listed in the repo inventory.
- **Not for constants.** "Don't use them to store shared strings or non-library constants" ([bp-deps] `#use_version_catalogs`). That covers `projectGroup` (`gradle.properties:2`).
- **Overriding has limits.** "Overwriting a version only affects what is imported and used when declaring dependencies. The actual resolved dependency version may differ due to conflict resolution" ([vc] `#sec:overwriting-catalog-versions`).

## 3. Convention plugins and `build-logic`

### 3.1 `buildSrc` versus an included `build-logic` build

- **Gradle's preference** ([bp-struct] `#favor_composite_builds`):
  - "The preferred location for build logic is an included build (typically named build-logic), not in buildSrc."
  - "Any change in buildSrc causes the entire build to become out-of-date, whereas changes in a subproject of an included build only cause projects in the build using the products of that particular subproject to be out-of-date."
  - "included builds are treated just like external dependencies, which is a simpler mental model."
  - "The buildSrc project automatically applies the java plugin, which may be unnecessary."
- **Caveat on mixed plugin sets.** "Applying a different set of build-logic plugins to the subprojects in your including build will result in a different classpath being used for each" (same page).
- **buildSrc is implicit.** "Gradle automatically compiles the code in buildSrc and includes it in the classpath of all build scripts." Its changes "invalidate the configuration phase and require re-execution of all tasks" ([sharing] `#sec:using_buildsrc`).
- **The composite-builds page.** "Composite builds are the recommended way to structure and share custom build logic across projects" ([composite] `#composite_build_use_cases`).
- **Where to include it.** Gradle shows both forms.
  - The best-practices page uses top-level `includeBuild("build-logic")` under `#favor_composite_builds`, and `pluginManagement { includeBuild("build-logic") }` under `#use_convention_plugins` ([bp-struct]).
  - Plugins applied in project scripts "can be included using includeBuild"; settings plugins need `includeBuild` inside `pluginManagement {}` ([composite] `#including_plugin_builds_in_settings`).
- **Isolation.** "Included builds do not share any configuration with the root build … This includes repositories, plugin management, or dependency versions defined in buildSrc or version catalogs." Also, "gradle.properties files defined in the root build are not visible to included builds" ([composite] `#composite_build_isolation`). Today the plugin versions live only in the root `gradle.properties` (`:4-10`).

### 3.2 Applying third-party plugins in precompiled script plugins

- **The artifact goes on `implementation`.** "In order to apply an external plugin in a precompiled script plugin, it has to be added to the plugin project's implementation classpath in the plugin's build file", for example `implementation("com.bmuschko:gradle-docker-plugin:6.4.0")`, then `plugins { id("com.bmuschko.docker-remote-api") }` ([precompiled] `#sec:applying_external_plugins`).
- **No version or `apply false`.** "The version "…" and apply false syntax are not supported in precompiled script plugins. The version for the plugin is determined by the version declared in the plugin's build file" (same anchor).
- **Plugin ids.** A script `code-quality.gradle.kts` in package `my` "would be exposed as the my.code-quality plugin" ([precompiled] `#sec:the_plugin_id`).
- **Artifact versus id.** "The dependency artifact coordinates (GAV) for a plugin can be different from the plugin id" ([sample-94]). The catalog page shows a helper that maps a catalog plugin alias to marker coordinates `"${pluginId}:${pluginId}.gradle.plugin:${version}"` ([vc] `#sec:buildsrc-version-catalog`).

### 3.3 Plugin artifact coordinates (marker POMs fetched 2026-10-01, all HTTP 200)

| Plugin id @ repo version | Marker POM | Artifact it depends on | Notes |
| --- | --- | --- | --- |
| `org.jetbrains.kotlin.jvm` @ 2.3.21 | [marker][m-kjvm] | `org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21` | Also on Maven Central. |
| `org.jetbrains.kotlin.kapt` @ 2.3.21 | [marker][m-kapt] | `org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21` | kapt ships inside KGP (`META-INF/gradle-plugins/org.jetbrains.kotlin.kapt.properties` in the jar). |
| `org.jetbrains.kotlin.plugin.spring` @ 2.3.21 | [marker][m-kspring] | `org.jetbrains.kotlin:kotlin-allopen:2.3.21` | |
| `org.jetbrains.kotlin.plugin.jpa` @ 2.3.21 | [marker][m-kjpa] | `org.jetbrains.kotlin:kotlin-noarg:2.3.21` | |
| `org.springframework.boot` @ 4.1.1 | [marker][m-boot] | `org.springframework.boot:spring-boot-gradle-plugin:4.1.1` | Also on Maven Central. Its runtime variant depends on `io.spring.gradle:dependency-management-plugin:1.1.7`. |
| `io.spring.dependency-management` @ 1.1.7 | [marker][m-dm] | `io.spring.gradle:dependency-management-plugin:1.1.7` | Also on Maven Central. |
| `org.jlleitschuh.gradle.ktlint` @ 14.2.0 | [marker][m-ktlint] | `org.jlleitschuh.gradle:ktlint-gradle:14.2.0` | **Not on Maven Central** (404). Only the Plugin Portal has it. |
| `jacoco` | none | none | A core Gradle plugin, applied as `plugins { jacoco }` ([jacoco]). |
| `kotlin-dsl` (for build-logic itself) on Gradle 9.7.1 | [marker][m-kdsl] | `org.gradle.kotlin:gradle-kotlin-dsl-plugins:6.7.3` | The 9.7.1 distribution pins `kotlin-dsl` 6.7.3 (`gradle-kotlin-dsl-9.7.1.jar`). |

### 3.4 Sharing the root catalog with `build-logic`

- **The documented import.** "The version catalog builder API allows importing a catalog from an external file, enabling reuse across different parts of a build, such as sharing the main build's catalog with buildSrc." The example is `dependencyResolutionManagement { versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } } }` ([vc] `#sec:sharing-catalogs`).
- **buildSrc only, in the docs.** "buildSrc does not automatically inherit the version catalog from the main project" ([vc] `#sec:buildsrc-version-catalog`). The docs show this for `buildSrc` only. An included `build-logic` is isolated in the same way (§3.1). The same settings snippet worked for a `build-logic` build in the trial (§5.6).

### 3.5 Reading catalog entries in a precompiled script plugin

- **Documented route.** "In precompiled script plugins inside buildSrc, the version catalog can be accessed using extensions.getByType(VersionCatalogsExtension)", for example `libs.findLibrary("guava").get()`. "However, the plugins block in the precompiled script plugin cannot access the version catalog" ([vc] `#sec:buildsrc-version-catalog`).
- **The tracking issue.** [gradle/gradle#15383][gh-15383] is titled "Make generated type-safe version catalogs accessors accessible from precompiled script plugins".
  - Status: open; labels `a:feature`, `in:kotlin-dsl`, `in:precompiled-script-plugin` and `in:dependency-version-catalog`; no milestone. Created 2020-12-01, last updated 2026-06-22, 171 comments (`gh api repos/gradle/gradle/issues/15383`, read 2026-10-01).
  - Its body: "the "version catalog" that a precompiled script plugin should see cannot be the catalog declared in the "main build"."
- **The widely used workaround** comes from a 2021-02-16 comment by a Gradle contributor ([gh-15383-wa]).
  - Add `implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))` to build-logic's dependencies.
  - Then use `val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()` in the script.
  - The author's caveat: it "depends on the same catalog being applied to the main build and the plugin build".
  - No 9.7.1 Gradle doc page mentions `protectionDomain`, `codeSource` or `LibrariesForLibs`.

### 3.6 `kotlin-dsl` and the embedded Kotlin version

- **Gradle 9.7.1 embeds Kotlin 2.4.0.**
  - The compatibility table row reads "2.4.0 | 9.7.0 | 2.2" (embedded version, first Gradle, language version) ([compat]).
  - The 9.7.1 distribution ships `kotlin-stdlib-2.4.0.jar` (`~/.gradle/wrapper/dists/gradle-9.7.1-bin/…/lib`).
  - The source pins `kotlin = "2.4.0!!"` at tag `v9.7.1`.
  - The project's KGP is 2.3.21 (`gradle.properties:4`).
- **Language level of precompiled scripts.** `kotlin-dsl` sets `apiVersion` and `languageVersion` to `KOTLIN_2_2` on every `KotlinCompile` task of the project that applies it ([kotlin-dsl-compiler-src]).
- **Docs on version pairing.** "Each Gradle release is meant to be used with a specific version of the kotlin-dsl plugin. Compatibility between arbitrary Gradle releases and kotlin-dsl plugin versions is not guaranteed" ([kotlin-dsl] `#sec:kotlin-dsl_plugin`). And: "The primary compatibility concern lies between the external kotlin-gradle-plugin version and the kotlin-stdlib version shipped with Gradle … As long as the versions are compatible, everything should work as expected" ([kotlin-dsl] `#forward_compatibility`).
- **The warning.**
  - Text, from [embedded-kotlin-src] at `v9.7.1`: "WARNING: Unsupported Kotlin plugin version." … "The `embedded-kotlin` and `kotlin-dsl` plugins rely on features of Kotlin `$embeddedKotlinVersion` that might work differently than in the requested version `$kotlinVersion`." … "Using the `kotlin-dsl` plugin together with a different Kotlin version (for example, by using the Kotlin Gradle plugin (`kotlin(jvm)`)) in the same project is not recommended."
  - It fires when a different KGP version is applied in the project that applies `kotlin-dsl`.
  - Putting KGP 2.3.21 on build-logic's `implementation` classpath is a different case, and no doc addresses it. The trial in §5.6 printed no warning.
- **Kotlin's side.** KGP 2.3.20–2.3.21 lists Gradle 7.6.3–9.3.0 as fully supported. Above that, "you might encounter deprecation warnings or some new features might not work" ([kgp]). The repo already runs KGP 2.3.21 on Gradle 9.7.1, as generated by Initializr for Boot 4.1.1 ([init-zip]).

### 3.7 Cross-project configuration, Isolated Projects and the configuration cache

- **Cross-project configuration.** "An improper way to share build logic between subprojects is cross-project configuration via the subprojects {} and allprojects {} DSL constructs." It "can also introduce configuration-time coupling between projects, which can prevent optimizations like configuration-on-demand from working properly" ([sharing] `#sec:convention_plugins_vs_cross_configuration`). "Convention plugins are preferred over allprojects {} / subprojects {} blocks" ([convention-impl]).
- **Isolated Projects.** It "is an incubating feature", promoted from experimental in 9.7.0. Under it, a project may not "observe or change … mutable state of other projects", including `group`, `version`, `tasks`, `dependencies`, `repositories`, `plugins` and `extensions`. Traversal with `subprojects()`/`allprojects()` is allowed, but mutating the projects it returns is not. Convention plugins comply "because each project applies the plugins to itself" ([isolated] `#sec:constraints`, `#sec:convention_plugins`). Every block in `build.gradle.kts:29-123` that touches another project mutates that project. §5.6 has a measured run.
- **Configuration cache.** "This feature will be enabled by default in Gradle 10" and "Since Gradle 9.0.0, the Configuration Cache is the preferred mode of execution" ([cc]).
- **The repo's patterns against these pages:**
  - `providers.exec { … }` is supported. But "if the value of the provider is used at configuration time then it will become a build configuration input" ([cc-req] `#config_cache:requirements:external_processes`). That is `build.gradle.kts:9`.
  - `afterEvaluate` "is incompatible with the Configuration Cache" ([bp-general] `#avoid_after_evaluate`). That is `build.gradle.kts:104`. The measured run in §5.6 still stored a cache entry with no problems.
  - `tasks.withType(X::class) { … }` and `tasks.withType<X> { … }` are eager. "adding a closure (e.g., tasks.withType(Class) { … }) makes it an eager API. For a lazy alternative, use withType().configureEach()" ([tca] `#sec:how_do_i_defer_configuration`). That is `build.gradle.kts:80-81,84-85,96`.
  - `Project.getProperties()` and `val x by project` are deprecated for Gradle 10. The migration advice is to "read them in subprojects via providers.gradleProperty("name")" or to "extract into a convention plugin applied to each subproject" ([upgrading9] `#deprecated_get_properties`).

### 3.8 Repositories in settings

- **Central declaration.** "Instead of declaring repositories in every subproject of your build or via an allprojects block, Gradle provides a way to declare them centrally". "Central declaration of repositories is an incubating feature" ([central-repos]).
- **Modes** ([central-repos] `#sec:available-modes`; [repos-mode]):
  - `PREFER_PROJECT` (the default): project repositories override settings.
  - `PREFER_SETTINGS`: "any repository declared directly in a project, either directly or via a plugin, will be ignored", with a warning.
  - `FAIL_ON_PROJECT_REPOS`: a project repository "will trigger a build error".
  - The repo's `allprojects { repositories { mavenCentral() } }` (`build.gradle.kts:34-36`) is a project-level declaration in this sense.
- **Best-practice wording.** "While dependencyResolutionManagement.repositories is an incubating API, it is the preferred way of declaring repositories". Its "Do This" example sets `repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS` ([bp-deps] `#set_up_repositories_in_settings`).
- **Plugin repositories are separate.** They live in `pluginManagement { repositories {} }`. Exclusive content filters there make "it … illegal to add more repositories through the project buildscript.repositories" ([filtering] `#sec:declaring-content-repositories`). Release- or snapshot-only filters are `mavenContent { releasesOnly() }` / `snapshotsOnly()` ([filtering] `#sec:maven_repository_filtering`).

### 3.9 Kotlin's Gradle best practices

The page is dated 19 August 2026 ([kt-bp]). Items:
- **Use Kotlin DSL**, "to gain the benefits of strict typing".
- **Use a version catalog**, "in a libs.versions.toml file to centralize dependency management".
- **Use convention plugins**, "to encapsulate and reuse common build logic across multiple build files".
- **Use local build cache.**
- **Use configuration cache.** It "implicitly enables the org.gradle.parallel property".
- **Improve build times for multiple targets.** Multiplatform only.
- **Migrate from kapt to KSP**: "KSP is faster and more efficient than kapt". The repo uses kapt for QueryDSL (`build.gradle.kts:41` and the 4 `kapt(...)` sites).
- **Use modularization.** "Modularization only benefits projects of moderate to large size."
- **Set up CI/CD.**
- **Use remote build cache.**

The page has no item on project isolation or on keeping Kotlin current.

### 3.10 Official samples

- **The 9.7.1 sample is gone.** `https://docs.gradle.org/9.7.1/samples/sample_convention_plugins.html` returns 404, as it does for 9.5.0–9.7.0. The samples moved into user-guide snippets ([sample-971]; `curl`, 2026-10-01).
- **The 9.4.0 sample** ([sample-94]) uses `buildSrc` with `myproject.java-conventions` and `myproject.library-conventions`. It notes "applying a convention plugin to a subproject effectively declares its type".
- **The 9.4.0 build-logic sample** ([sample-94-bl]) uses an included build named `build-conventions`, added with a top-level `includeBuild`.
- **The 9.7.1 replacement** is [convention-impl] `#creating_a_convention_plugin`. It lists "buildSrc/ — simplest approach" and "an included build (commonly named build-logic/) — more scalable". [bp-struct] `#use_convention_plugins` shows plugins `my.base-java-library`, `my.java-library` and `my.java-use-junit5`, and notes "Convention plugins can apply other convention plugins".

## 4. Spring Boot's side

### 4.1 io.spring.dependency-management versus `platform(SpringBootPlugin.BOM_COORDINATES)`

- **Both documented, no preference.** "you can either apply the io.spring.dependency-management plugin or use Gradle's native bom support. The primary benefit of the former is that it offers property-based customization of managed versions, while using the latter will likely result in faster builds" ([boot-deps]). The page states no preference, and the [Getting Started page][boot-gs] lists both as equal options.
- **With the plugin.** "Spring Boot's plugin will automatically import the spring-boot-dependencies bom" ([boot-deps] `#managing-dependencies.dependency-management-plugin`; [boot-reacting] `#reacting-to-other-plugins.dependency-management`).
- **With the native platform.**
  - Boot's example is `implementation(platform(SpringBootPlugin.BOM_COORDINATES))`.
  - "A platform dependency treats the versions in the bom as recommendations and other versions and constraints in the dependency graph may cause a version of a dependency other than that declared in the bom to be used."
  - "An enforcedPlatform dependency treats the versions in the bom as requirements and they will override any other version found in the dependency graph."
  - "A platform or enforced platform will only constrain the versions of the configuration in which it has been declared or that extend from the configuration in which it has been declared. As a result, in may be necessary to declare the same dependency in more than one configuration" ([boot-deps] `#managing-dependencies.gradle-bom-support`). "in may" is the page's own typo.
  - The Boot team's issue answers name `developmentOnly` and `annotationProcessor` as configurations that need their own `platform(...)` line ([boot-41432]).
- **`BOM_COORDINATES`** is `"org.springframework.boot:spring-boot-dependencies:" + SPRING_BOOT_VERSION`. The version is read from the plugin jar's `Implementation-Version` (4.1.1) ([sbp-src]). Its Javadoc says it is `null` if no version can be extracted. "The constant's primarily for those who aren't using the dependency management plugin and want to use Gradle's platform support instead" ([dmp-398]).

### 4.2 Overriding a managed version

- **With the plugin.** "To customize a managed version you set its corresponding property", for example `extra["slf4j.version"] = "1.7.20"` ([boot-deps] `#managing-dependencies.dependency-management-plugin.customizing`). The property names are listed in [boot-props]; the 4.1.1 BOM declares `mockito.version` 5.23.0 and `kotlin.version` 2.3.21 ([bom411]).
- **With the plugin, other forms.** The plugin also accepts `mavenBom(…) { bomProperty(…) }` and project properties, with the caveat "You should only use this approach if you do not intend to generate and publish a Maven pom" ([dmp] `#dependency-management-configuration-bom-import-override-property`).
- **With the platform.** "you cannot use the properties from spring-boot-dependencies to control the versions of the dependencies that it manages. Instead, you must use one of the mechanisms that Gradle provides. One such mechanism is a resolution strategy" ([boot-deps] `#managing-dependencies.gradle-bom-support.customizing`). Boot shows only `resolutionStrategy.eachDependency { useVersion(…) }`. Gradle's own mechanisms are those in §2.4: `strictly` ("can downgrade a version"), constraints, and `enforcedPlatform`.
- **The Boot team's caveat on any override.** "Each Spring Boot release is designed and tested against a specific set of third-party dependencies. Overriding versions may cause compatibility issues and should be done with care" ([boot-deps]).

### 4.3 `overriddenByDependencies` and the resolution rule

- **Scope.** "Dependency management can be applied to every configuration (the default) or to one or more specific configurations" ([dmp] `#dependency-management-configuration`). The source registers its rule on `project.getConfigurations().all(…)`. The rule is `resolutionStrategy.eachDependency { details.useVersion(version) }`, which Gradle reports as "selected by rule".
- **Direct versions override, for the whole graph.** Declaring a dependency with a version "will cause any dependency (direct or transitive) on com.zaxxer:HikariCP in the implementation configuration to use version 5.0.0, overriding any dependency management that may exist". The behaviour can be turned off with `dependencyManagement { overriddenByDependencies(false) }`. The default is `true` (`DependencyManagementSettings`) ([dmp] `#dependency-management-configuration-bom-import-override-dependency-management`).
- **This is the mockito case.** `testImplementation("org.mockito:mockito-core:5.14.0")` (`build.gradle.kts:70`) becomes the managed version for the test configurations. The 5.23.0 that `mockito-junit-jupiter` requests is then rewritten to 5.14.0. The same pattern was reported with Tomcat, and the maintainer answered: "This is an intentional feature that allows the dependency management to be overridden" ([dmp-406]).
- **Constraints lose to the rule.** "This is due to your project's use of the dependency management plugin which uses a resolution strategy to enforce versions. This trumps any dependency constraints that have been configured" ([boot-26840]). Applying management as constraints instead is an open request ([dmp-330]).
- **Build-tool configurations.** The plugin also manages the Kotlin plugin's own configurations (`kotlinCompilerClasspath`, `kotlinBuildToolsApiClasspath`); that is the open [dmp-407]. Per-configuration inclusion and exclusion is the open [dmp-369], milestone 1.2.x. Its maintainer wrote on 2024-06-19: "I don't expect a 1.2 release of the dependency management plugin in the near-term. In the meantime, I would recommend using Gradle's built-in platform support in place of the dependency management plugin" ([dmp-369]). §5.3 measures this repo's case.

### 4.4 Exclusions

- **The plugin applies POM exclusions.** "Gradle does not honour Maven's semantics when it is using the pom to build the dependency graph. A notable difference that results from this is in how exclusions are handled." The plugin "improves Gradle's handling of exclusions that have been declared in a Maven pom by honoring Maven's semantics" ([dmp] `#maven-exclusions`). It is on by default and can be disabled with `applyMavenExclusions(false)`.
- **Cost.** The docs carry no performance note. The source resolves a copy of each transitive configuration to find exclusions. The maintainer wrote: "With hindsight, adding support for Maven-style exclusions to this plugin was a mistake. It's easily the most complex part of the code and has had a number of performance problems over the years" ([dmp-331], open).
- **Feature differences, per the maintainer.** "1. Applies versions in an imported bom as Maven intends… 2. Allows properties in a bom to be overridden… 3. Applies exclusions declared in a dependency's pom as Maven intended". Later: "Gradle 5 addresses bullet 1 with the concept of an enforced platform. Bullets 2 and 3 have not been addressed" ([dmp-211]).
- **The repo** declares no `exclude` of its own.

### 4.5 The Boot plugin on library modules

- **What the plugin does to a Java project.** With `java` applied it creates `bootJar`, makes `assemble` depend on it, and "Configures the jar task to use plain as the convention for its archive classifier". The `jar` task is reclassified, not disabled ([boot-reacting] `#reacting-to-other-plugins.java`; `JavaPluginAction.java` at `v4.1.1`). It also creates `bootRun`, `bootTestRun`, `bootBuildImage`, `developmentOnly`, `testAndDevelopmentOnly` and `productionRuntimeClasspath`.
- **`bootJar` needs a main class.** The main class is found "by looking for a class with a public static void main(String[]) method in the main source set's output" ([boot-packaging] `#packaging-executable.configuring.main-class`). If none is found, `ResolveMainClassName` throws "Main class name has not been configured and it could not be resolved from classpath" ([resolve-main-src]). That is why the repo disables `BootJar` outside `apps` (`build.gradle.kts:80-86`).
- **The reference's guidance.** "a Spring Boot application is not intended to be used as a dependency … the recommended approach is to move that code into a separate module" ([boot-howto-build]).
  - The reference gives no explicit rule for library modules. The nearest is "Using Spring Boot's Dependency Management in Isolation": "Spring Boot's dependency management can be used in a project without applying Spring Boot's plugin to that project", with the plugin declared `apply false` ([boot-deps] `#managing-dependencies.dependency-management-plugin.using-in-isolation`).
  - The Spring guide "Creating a Multi Module Project" says "the Spring Boot plugin is *not* used in the library project at all" ([spring-guide-mm]).
- **What a library module loses without the Boot plugin.** The Boot plugin sets `javaParameters = true` on Kotlin compilation and `-parameters` on Java compilation ([boot-reacting]). It also sets `kotlin.version` (§4.6).

### 4.6 Reaction to the Kotlin plugin

- **What the docs say.** "Aligns the Kotlin version used in Spring Boot's dependency management with the version of the plugin. This is achieved by setting the kotlin.version property with a value that matches the version of the Kotlin plugin". It also sets `-java-parameters` on `KotlinCompile` ([boot-reacting] `#reacting-to-other-plugins.kotlin`).
- **What the source does.** `KotlinPluginAction` sets the extra property `kotlin.version` unless it already exists, whether or not the dependency-management plugin is applied ([kotlin-action-src]).
- **Only the plugin reads it.** The only reader is the dependency-management plugin's property source. "That property is specific to Spring Boot's spring-boot-dependencies bom" ([boot-11711]). With `platform()` the alignment has no effect ([boot-deps] `#managing-dependencies.gradle-bom-support.customizing`).
- **For this repo** the BOM's `kotlin.version` (2.3.21) equals the plugin version (`gradle.properties:4`), so both setups resolve `kotlin-stdlib` 2.3.21 (§5.2).

### 4.7 The Spring team's position and Initializr

- **No deprecation planned while property overrides lack a Gradle equivalent.** "While our long term goal is to no longer have to maintain the dependency management plugin, until Gradle supports an equivalent of the version property overrides that are possible with the dependency management plugin (and with Maven) we won't be able to do so" ([boot-21723]).
- **Initializr keeps the plugin.** On a request to stop using it there: "I'd love the need for it to go away but I'm not sure that we're there yet … the main remaining benefit … is that you can use a property to override Spring Boot's dependency management" ([init-1414], closed not planned).
- **Gradle 9 support.** The plugin's docs list "Gradle 6.x (6.8 or later), 7.x, or 8.x" ([dmp] `#requirements`). The docs describe 1.1.7, last updated 2024-12-17. Boot 4.1.1 depends on it and requires Gradle 8.14+ or 9.x. No dependency-management-plugin source states Gradle 9 support.
- **What Initializr generates.** For Boot 4.1.1 (Kotlin, Gradle Kotlin DSL) it writes `kotlin("jvm") version "2.3.21"`, `kotlin("plugin.spring") version "2.3.21"`, `id("org.springframework.boot") version "4.1.1"` and `id("io.spring.dependency-management") version "1.1.7"`, with a Gradle 9.7.1 wrapper ([init-zip], fetched 2026-10-01). The project is a single module with no catalog.

## 5. Measured facts

### 5.1 Method

- **Copies.** Three throwaway clones of `5adb984` were made with `git clone --local` in the session scratchpad: `repo-baseline` (unchanged), `repo-copy` (platform trial) and `repo-buildlogic` (§5.6). None was kept or pushed.
- **The platform trial** changes only `build.gradle.kts`:
  - It removes `id("io.spring.dependency-management")` (`:19`) and `apply(plugin = "io.spring.dependency-management")` (`:44`).
  - It adds `implementation(platform(SpringBootPlugin.BOM_COORDINATES))` and `kapt(platform(…))` to the `subprojects` dependencies.
  - It adds `"testFixturesImplementation"(platform(…))` inside `plugins.withId("java-test-fixtures")`.
  - No version and no other declaration changes.
- **Recording.** An init script registers a `dumpClasspaths` task in every project. The task walks `incoming.resolutionResult` of every resolvable configuration. Each module node is recorded with its selected version, its variant category (`library` or `platform`), every requested version on its incoming edges, and Gradle's selection causes.
- **Counting.** A difference is one (module, configuration, coordinate) triple whose selected version differs, or that exists on one side only. BOM (platform) nodes are counted apart from libraries. Sources of higher requests were traced with `dependencyInsight` in the trial copy.

### 5.2 The four requested classpaths

`compileClasspath`, `runtimeClasspath`, `testCompileClasspath` and `testRuntimeClasspath`, for all 9 modules (36 pairs):

| Coordinate | Baseline (plugin) | Trial (platform) | Where (count) | Who asks for the higher version |
| --- | --- | --- | --- | --- |
| `org.mockito:mockito-core` | 5.14.0, "selected by rule" | **5.23.0** | All 9 modules, both test classpaths (18) | `mockito-junit-jupiter` 5.23.0, through `spring-boot-starter-test`. Other requests: the direct pin 5.14.0 (`build.gradle.kts:70`, `gradle.properties:14`) and `mockito-kotlin` 5.4.0, which asks for 5.12.0. |
| `org.slf4j:slf4j-api` | 2.0.18 (BOM) | **2.0.19** | All 9 modules, both test classpaths (18) | `instancio-core` 6.1.0, through `instancio-junit`/`instancio-kotlin`, and in commerce-api also `archunit` 1.5.1. |
| `com.fasterxml.jackson.core:jackson-core`, `jackson-databind`, `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`, `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.21.5 (BOM `jackson-2-bom.version`) | **2.22.1** | commerce-api, all 4 classpaths (16) | `swagger-core-jakarta` 2.2.55, through `springdoc-openapi-starter-webmvc-ui` 3.1.1 (`apps/commerce-api/build.gradle.kts:25`). `jackson-bom` 2.22.1 aligns the rest. |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 | **2.22** | commerce-api, all 4 classpaths (4) | Same as above. |
| `org.junit.jupiter:junit-jupiter`, `-api`, `-engine`, `-params`; `org.junit.platform:junit-platform-commons`, `-engine`, `-launcher` | 6.0.3 (BOM) | **6.1.3** | commerce-api `testRuntimeClasspath` only (7) | `archunit-junit6-engine-api` 1.5.1 asks for `junit-platform-engine` 6.1.3 (`apps/commerce-api/build.gradle.kts:41`). `junit-bom` 6.1.3 then aligns the other six. |

- **Totals.** 63 differing triples, 14 distinct coordinates. Every change is upward and none is downward. No library node appears or disappears.
- **Unchanged.** `kotlin-stdlib` is 2.3.21 on both sides. commerce-batch, commerce-streamer, jpa, redis, kafka and the three supports modules differ only in `mockito-core` and `slf4j-api`.
- **BOM nodes.** Each of the 36 pairs gains exactly one platform node, `spring-boot-dependencies:4.1.1`. Two BOM nodes move inside commerce-api: `jackson-bom` 2.21.5 → 2.22.1 (4 classpaths) and `junit-bom` 6.0.3 → 6.1.3 (`testRuntimeClasspath`).
- **Node counts, baseline → trial.** The `compile/runtime/testCompile/testRuntime` counts are:

  | Module | Baseline | Trial |
  | --- | --- | --- |
  | commerce-api | 128 / 152 / 204 / 239 | +1 each |
  | commerce-batch | 90 / 136 / 153 / 206 | +1 each |
  | commerce-streamer | 110 / 148 / 168 / 213 | +1 each |
  | jpa | 61 / 73 / 122 / 138 | +1 each |
  | redis | 60 / 64 / 118 / 128 | +1 each |
  | kafka | 43 / 47 / 115 / 157 | +1 each |
  | supports:jackson | 37 / 38 / 95 / 104 | +1 each |
  | supports:logging | 65 / 69 / 123 / 135 | +1 each |
  | supports:monitoring | 49 / 53 / 107 / 119 | +1 each |

- **Where the baseline selects below a request.** 7 coordinates are selected below a version that some edge requests: `mockito-core`, `slf4j-api`, `junit-platform-engine`, `jackson-annotations`, `jackson-databind`, `jackson-dataformat-yaml` and `jackson-datatype-jsr310`. The other 7 changed coordinates move only through BOM alignment.
- **The BOM reaches consumers.** With `implementation(platform(…))`, the BOM is part of each module's `runtimeElements`. `dependencyInsight` in the trial shows it reaching the apps through `project(':modules:jpa')` and the other project dependencies.

### 5.3 Other resolvable configurations

- **Test fixtures and kapt.** `testFixturesCompileClasspath`, `testFixturesRuntimeClasspath` (jpa, redis, kafka), `kapt`, `kaptTest` and `kaptClasspath_*` show no library differences. They need their own `platform(...)` line: the BOM node appears in them only through the added `kapt(platform)` and `testFixturesImplementation(platform)` lines. This matches Boot's note that a platform constrains only the configuration it is declared in and the ones extending it (§4.1).
- **Kotlin compiler classpaths.** `kotlinCompilerClasspath`, `kotlinBuildToolsApiClasspath` and `kotlinKlibCommonizerClasspath`, in all 9 modules:
  - `kotlin-reflect`: 2.3.21 (baseline, selected by rule) → 1.6.10 (trial);
  - `kotlinx-coroutines-core-jvm`: 1.10.2 → 1.8.0.
  - `kotlin-compiler-embeddable` 2.3.21 declares `kotlin-reflect` 1.6.10 and `kotlinx-coroutines-core-jvm` 1.8.0 at runtime scope ([kce-pom]). So in the baseline, the dependency-management plugin changes the Kotlin compiler's own classpath ([dmp-407]).
- **ktlint classpaths.** `ktlint`, `ktlintRuleset`, `ktlintBaselineReporter` and `ktlintReporter`, in all 9 modules:
  - `kotlin-compiler-embeddable`, `kotlin-stdlib`, `kotlin-script-runtime` and `kotlin-daemon-embeddable`: 2.3.21 → 2.2.21. 2.2.21 is what ktlint 1.8.0 declares (`docs/research/spring-boot-4-upgrade.md:361`).
  - `logback-classic`/`-core`: 1.5.38 → 1.3.16.
  - `slf4j-api`: 2.0.18 → 2.0.7.
  - `kotlinx-serialization-*`: 1.11.0 → 1.4.1.
  - `sarif4k`: 0.5.0 → 0.6.0.
  - The Boot 4 prototype recorded that the plugin's raise of ktlint's compiler broke ktlint during that spike (`docs/research/spring-boot-4-upgrade.md:514`). On `5adb984`, `ktlintCheck` passes in both setups (§5.4).
- **Totals.** 356 library differences across all configurations outside the four requested ones. Five of them are commerce-api's `productionRuntimeClasspath`, which mirrors its `runtimeClasspath`; the rest are the build-tool configurations above. The trial adds 57 `spring-boot-dependencies` nodes there and drops the `kotlinx-serialization-bom` node from `ktlintReporter` in all 9 modules.

### 5.4 Compile, tests, ktlint and resolution time

- **Compile.** `./gradlew compileTestKotlin compileTestFixturesKotlin` in the trial: BUILD SUCCESSFUL, 64 tasks executed, 0 Kotlin warnings (`^w:` lines).
- **Tests.** `./gradlew test --continue` (Docker 29.8.1 available): 429 tests, 0 failures, 0 errors, 0 skipped in the trial, and the same 429/0/0/0 in `repo-baseline`. Counted from `build/test-results/test/*.xml`. No test touches springdoc (`rg "api-docs|swagger|springdoc" apps/*/src/test` finds nothing). The Jackson 2 change is therefore not exercised by any test.
- **ktlint.** `./gradlew ktlintCheck` in the trial: BUILD SUCCESSFUL, 51 tasks.
- **Resolution time.** `./gradlew -q --offline --init-script … dumpClasspaths --rerun-tasks`, warm daemon, 5 runs each, alternating:
  - baseline 2.26 / 2.29 / 2.30 / 2.33 / 2.34 s;
  - trial 1.12 / 1.14 / 1.14 / 1.16 / 1.17 s.

  `./gradlew -q --offline help` took 0.81–0.86 s in both setups (6 runs each). Resolving every resolvable configuration therefore cost about 1.5 s with the plugin and about 0.3 s with the platform. One machine, small n, and the times include the init script's own graph walk.
- **Unresolved.** Nothing failed to resolve in either setup: no `UnresolvedDependencyResult` in any configuration.

### 5.5 What the root `subprojects { dependencies {} }` block puts on module classpaths

- **How the count was made.**
  - For each of the 16 declarations at `build.gradle.kts:57-77`, the package prefixes come from the artifact's own jar. For starters, which are empty, they come from the starter's direct dependencies. Package names were spot-checked in the jars in the Gradle cache.
  - The count is the number of `.kt` files whose `import` lines match those prefixes (`ggrep -rlP '^import (…)'`).
  - Main-scope declarations are checked against `src/main`, `src/test` and `src/testFixtures`. Test-scope declarations are checked against `src/test` only, because test fixtures do not see `testImplementation`.
  - Grep cannot see runtime-only use, so three declarations are marked runtime-only.
- **Source files per module** (`main` / `test` / `testFixtures`):
  - commerce-api 101/43/0, commerce-batch 6/2/0, commerce-streamer 2/0/0;
  - jpa 4/0/4, redis 3/0/2, kafka 1/0/0;
  - supports:jackson 1/1/0, supports:logging 0/0/0, supports:monitoring 0/0/0.

| Line | Declaration | api | batch | streamer | jpa | redis | kafka | jackson | logging | monitoring |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 57 | `runtimeOnly` spring-boot-starter-validation | 33* | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 58 | `implementation` kotlin-reflect | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 60 | `implementation` spring-boot-starter | 60 | 8 | 2 | 5 | 4 | 1 | 1 | 0 | 0 |
| 62 | `implementation` jackson-module-kotlin | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 64 | `testRuntimeOnly` junit-platform-launcher | runtime | runtime | runtime | runtime | runtime | runtime | runtime | runtime | runtime |
| 66 | `testRuntimeOnly` mysql-connector-j | runtime | runtime | runtime | runtime | runtime | runtime | runtime | runtime | runtime |
| 67 | `testImplementation` spring-boot-starter-test | 37 | 2 | 0 | 0 | 0 | 0 | 1 | 0 | 0 |
| 68 | `testImplementation` kotlin-test-junit5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 69 | `testImplementation` springmockk | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 70 | `testImplementation` mockito-core:5.14.0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 71 | `testImplementation` mockito-kotlin | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 72 | `testImplementation` instancio-junit | 0† | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 73 | `testImplementation` instancio-kotlin | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 75 | `testImplementation` spring-boot-testcontainers | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 76 | `testImplementation` testcontainers | 0 | 0 | 0 | 0‡ | 0‡ | 0 | 0 | 0 | 0 |
| 77 | `testImplementation` testcontainers-junit-jupiter | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

Notes:
- \* **Validation.** These 33 files compile because commerce-api also declares the starter as `implementation` (`apps/commerce-api/build.gradle.kts:23`). The root line is `runtimeOnly`, so it cannot be the compile-time source.
- † **instancio-junit.** No file imports `org.instancio.junit`. `InstancioExtension` is registered through `apps/commerce-api/src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension:1`, with `junit.jupiter.extensions.autodetection.enabled=true` (`junit-platform.properties`). `org.instancio.Instancio`, from the transitive `instancio-core`, is imported by `BrandFixtures.kt:5` and `ProductFixtures.kt:8`.
- ‡ **testcontainers.** The imports are in the test fixtures (`MySqlTestContainersConfig.kt:4-5`, `RedisTestContainersConfig.kt:4-5`). They compile against the modules' own `testFixturesImplementation` lines (`modules/jpa/build.gradle.kts:25`, `modules/redis/build.gradle.kts:8`).
- **kotlin-reflect.** The one import is `kotlin.reflect.full.memberProperties` (`LayeredArchitectureTest.kt:18`).
- **jackson-module-kotlin** has no import anywhere. Boot 4 registers classpath Jackson modules by default (`docs/research/spring-boot-4-upgrade.md:152`). `supports/jackson/build.gradle.kts:6` declares it a second time.
- **mysql-connector-j** already reaches the `runtimeClasspath` of all three apps through `modules/jpa/build.gradle.kts:20` (`runtimeOnly`), as the baseline dump shows.
- **spring-boot-starter.** Its counts include `org.springframework.*` imports that other starters also provide.

Summary of the table:
- **No import in any module: 10 declarations.** `jackson-module-kotlin`, `kotlin-test-junit5`, `springmockk`, `mockito-core`, `mockito-kotlin`, `instancio-junit` (runtime-loaded in commerce-api), `instancio-kotlin`, `spring-boot-testcontainers`, `testcontainers` and `testcontainers-junit-jupiter`.
- **Runtime-only by design: 3.** `spring-boot-starter-validation` (as declared at the root), `junit-platform-launcher` and `mysql-connector-j`.
- **Imported somewhere: 3.** `kotlin-reflect` (1 module), `spring-boot-starter` (the 7 modules with sources) and `spring-boot-starter-test` (3 modules).
- **Modules without test sources.** Six modules have none: commerce-streamer, jpa, redis, kafka, logging and monitoring. All 12 test-scope declarations still land on their test classpaths.
  - supports:logging and supports:monitoring declare no test dependencies of their own. Their `testRuntimeClasspath` holds 66 more nodes than their `runtimeClasspath` (135 vs 69, 119 vs 53), all from the root block.

### 5.6 Other measured facts

- **Gradle 10 deprecations.** `./gradlew dumpClasspaths --warning-mode all` on the unchanged copy prints 15 Gradle 10 deprecations:
  - 4 for `val x: String by settings` (`settings.gradle.kts:17-20`);
  - 1 for `val projectGroup: String by project` (`build.gradle.kts:30`);
  - 10 for `Project.getProperties` (`build.gradle.kts:69-73,116`; `apps/commerce-api/build.gradle.kts:25,41`; `supports/logging/build.gradle.kts:9,11`).

  14 of the 15 are version lookups. This matches the "15 warnings" in ADR 0008 (`docs/adr/0008-upgrade-to-spring-boot-4-1-ahead-of-the-template.md:37`). Gradle's migration advice is in §3.7.
- **Configuration cache, unchanged copy.**
  - `./gradlew --configuration-cache compileTestKotlin` gave "Configuration cache entry stored". A second run gave "Reusing configuration cache", in 650 ms.
  - `./gradlew --configuration-cache build jacocoTestReport -m` also stored an entry, with no problems reported.
  - The `afterEvaluate` and configuration-time `.get()` (§1) did not block it. Per [cc-req], the `git rev-parse` output is a configuration input, so a new commit invalidates the entry.
- **Isolated Projects, unchanged copy.** `./gradlew --configuration-cache -Dorg.gradle.unsafe.isolated-projects=true help` fails with "Project ':' cannot access 'projectGroup' extension on subprojects via 'allprojects'" at `build.gradle.kts:30`. Gradle stops at the first violation, so the run does not list the rest.
- **build-logic trial** (`repo-buildlogic`, throwaway). The setup:
  - a `gradle/libs.versions.toml` with `kotlin = "2.3.21"` and `springBoot = "4.1.1"` and two plugin-artifact libraries;
  - `build-logic/settings.gradle.kts` importing it with `from(files("../gradle/libs.versions.toml"))`;
  - `build-logic/build.gradle.kts` applying `` `kotlin-dsl` `` with `implementation(libs.kotlin.plugin)` and `implementation(libs.springBoot.plugin)`;
  - two precompiled scripts, applied to `:supports:monitoring` through `pluginManagement { includeBuild("build-logic") }`.

  Results:
  - `./gradlew -p build-logic assemble` succeeds, and no "Unsupported Kotlin plugin version" warning appears.
  - A precompiled script with `plugins { id("org.jetbrains.kotlin.jvm") }` applies cleanly in a module where the root `subprojects {}` has already applied the same plugin.
  - `the<VersionCatalogsExtension>().named("libs").findVersion("kotlin")` returns 2.3.21.
  - `SpringBootPlugin.BOM_COORDINATES` evaluates to `org.springframework.boot:spring-boot-dependencies:4.1.1`.
  - The #15383 workaround (`files(libs.javaClass.superclass.protectionDomain.codeSource.location)` plus `the<LibrariesForLibs>()`) compiles and returns 2.3.21.
  - The same build stores a configuration-cache entry with no problems.
  - The main build auto-imported the new `gradle/libs.versions.toml` without a `versionCatalogs {}` block.

  Only one module and only the Kotlin jvm plugin were tried. kapt, allopen/noarg, Boot, dependency-management, ktlint and JaCoCo were not moved.

## 6. typesafe-conventions (added 2026-10-01, after the decision interview)

The decision interview picked this plugin over the routes in §3.5. The facts below come from its repository at the 0.11.1 tag and from two throwaway trials on clones of `5adb984`, run on Gradle 9.7.1.

- **Identity.**
  - It is a settings plugin, `dev.panuszewski.typesafe-conventions`. The latest version is 0.11.1, released 2026-05-24 ([tc]).
  - It is published on the Gradle Plugin Portal only, under the MIT license.
  - Its README requires Gradle 8.8+ and JDK 17+. CI tests Gradle 8.8–9.6.0, so 9.7.x is not in the matrix.
- **What it does.**
  - It is applied in `build-logic/settings.gradle.kts`, and it auto-imports every `gradle/*.versions.toml` of the parent build, so no `from(files(…))` is needed.
  - Precompiled scripts get `libs.*`, and `alias(libs.plugins.x)` works in their `plugins {}` block. For each alias it adds `implementation("<id>:<id>.gradle.plugin") { version { prefer(v) } }` to build-logic itself.
  - It also injects a `conventions` catalog into the parent build. In the trial, `loopers.kotlin-spring` became `conventions.plugins.loopers.kotlin.spring`.
- **How it works.**
  - Accessors are generated with Gradle's internal `LibrariesSourceGenerator`. Other internal types it uses: `SettingsInternal`, `GradleInternal`, `VersionCatalogBuilderInternal` and `DefaultVersionCatalog`.
  - It does not use the `protectionDomain`/`codeSource` workaround.
  - `alias(…)` in `plugins {}` is rewritten to `id("…")` by a regex over the extracted plugins block (`PluginVersionCatalogAccessors.kt:19`).
- **Trial results.** All of these were green on Gradle 9.7.1:
  - top-level `includeBuild("build-logic")`;
  - precompiled scripts in the default package;
  - `alias(conventions.plugins.loopers.kotlin.spring)` in `:supports:monitoring`;
  - the configuration cache stored and reused its entry;
  - no "Unsupported Kotlin plugin version" warning and no new deprecations.
  - build-logic's markers resolved at exactly the catalog versions: KGP 2.3.21, Boot 4.1.1 and dependency-management 1.1.7.
  - A base script that imports the BOM through `dependencyManagement { imports { mavenBom(…) } }`, with no Boot plugin, resolved monitoring's libraries at the baseline versions.
- **Limits observed.**
  - `-p build-logic` fails, because the plugin refuses a top-level build unless `allowTopLevelBuild = true` is set.
  - With `includeBuild` inside `pluginManagement`, a root settings `plugins {}` block makes build-logic "early-evaluated", and the build fails. A top-level `includeBuild` avoids this.
  - A script that references `SpringBootPlugin` compiles only because another script's `alias(libs.plugins.springBoot)` puts the Boot plugin on build-logic's shared compile classpath.
  - Isolated Projects is broken on 9.7+ for multi-project build-logic builds ([tc-186]). A single-project build-logic passed.
- **ktlint on build-logic.**
  - `./gradlew ktlintCheck` lints only the main build. `./gradlew ktlintCheck :build-logic:ktlintCheck` lints both, and precompiled scripts are checked.
  - The exclude must cover all of `build/generated-sources/`. Excluding only typesafe-conventions' output, as the README suggests, leaves 5,748 violations in kotlin-dsl's generated accessors.
  - Excluding only kotlin-dsl's output fails instead: an undeclared dependency on `generateEntrypointForConventions` is a build error on 9.7.1.
  - After the exclude was edited, the ktlint task stayed `UP-TO-DATE` until `--rerun-tasks` was used.

[tc]: https://github.com/radoslaw-panuszewski/typesafe-conventions-gradle-plugin
[tc-186]: https://github.com/radoslaw-panuszewski/typesafe-conventions-gradle-plugin/issues/186

## Repo inventory

Every version and coordinate that a catalog would hold, in the order of the TOML sections.
- **"Key"** applies the rules of §2.2 in order:
  - the first segment is the group's distinctive word without the TLD, and the doc's `jackson-databind` example uses the artifact's leading word when it names the project;
  - repeated words are dropped;
  - the rest of the artifact ID is camelCased;
  - plugin artifacts get `-plugin`.
- **Spring coordinates** follow the page's own example for a Spring Boot starter ("spring-boot-starter-web becomes springBootStarterWeb").
- **The rules leave choices** where marked ◊. The page allows several keys for one coordinate (§2.2). The column states what the rules produce, not a choice.
- **BOM-managed** means the Spring Boot 4.1.1 BOM manages the coordinate ([bom411]).

### `[versions]`

| Current location | Value | Used by | Key | BOM-managed |
| --- | --- | --- | --- | --- |
| `gradle.properties:4` `kotlinVersion` | 2.3.21 | 4 Kotlin plugin ids (`settings.gradle.kts:31-34`) | `kotlin` | BOM manages `kotlin.version` 2.3.21 for libraries; plugin versions are never BOM-managed |
| `gradle.properties:9` `springBootVersion` | 4.1.1 | Boot plugin (`settings.gradle.kts:35`) | `springBoot` | — |
| `gradle.properties:10` `springDependencyManagementVersion` | 1.1.7 | dependency-management plugin (`settings.gradle.kts:36`) | `springDependencyManagement` | — |
| `gradle.properties:6` `ktLintPluginVersion` | 14.2.0 | ktlint Gradle plugin (`settings.gradle.kts:37`) | `ktlintGradle` ◊ | — |
| `gradle.properties:7` `ktLintVersion` | 1.8.0 | ktlint engine, `version.set(...)` (`build.gradle.kts:116`); not a library or plugin entry | `ktlint` | No |
| `gradle.properties:12` `springDocOpenApiVersion` | 3.1.1 | `apps/commerce-api/build.gradle.kts:25` | `springdoc` | No |
| `gradle.properties:13` `springMockkVersion` | 4.0.2 | `build.gradle.kts:69` | `springmockk` | No |
| `gradle.properties:14` `mockitoVersion` | 5.14.0 | `build.gradle.kts:70` | `mockito` | **Yes**: BOM 5.23.0, overridden today (§4.3) |
| `gradle.properties:15` `mockitoKotlinVersion` | 5.4.0 | `build.gradle.kts:71` | `mockitoKotlin` | No |
| `gradle.properties:16` `instancioJUnitVersion` | 6.1.0 | `build.gradle.kts:72-73` (two artifacts) | `instancio` | No. Instancio 6 also publishes `instancio-bom` (`docs/research/spring-boot-4-upgrade.md:283`) |
| `gradle.properties:17` `archUnitVersion` | 1.5.1 | `apps/commerce-api/build.gradle.kts:41` | `archunit` | No |
| `gradle.properties:18` `slackAppenderVersion` | 1.6.1 | `supports/logging/build.gradle.kts:9` | `logbackSlackAppender` ◊ | No |
| `gradle.properties:19` `kotlinLoggingVersion` | 8.0.4 | `supports/logging/build.gradle.kts:11` | `kotlinLogging` | No |

These are not versions and do not belong in a catalog ("Don't use them to store shared strings or non-library constants", [bp-deps]):
- `projectGroup=com.loopers` (`gradle.properties:2`, read at `build.gradle.kts:30`);
- `kotlin.daemon.jvmargs` (`gradle.properties:20`);
- the Java toolchain 25 (`build.gradle.kts:25,49`).

JaCoCo has no version in the repo. It uses Gradle 9.7.1's default 0.8.14 (`docs/adr/0008-upgrade-to-spring-boot-4-1-ahead-of-the-template.md:17`).

### `[plugins]`

The page has no `[plugins]` naming rule. Keys apply the library rules to the plugin id.

| Plugin id | Applied at | Version from | Key |
| --- | --- | --- | --- |
| `org.jetbrains.kotlin.jvm` | `build.gradle.kts:15,40` | `kotlin` | `kotlin-jvm` |
| `org.jetbrains.kotlin.kapt` | `build.gradle.kts:16,41` | `kotlin` | `kotlin-kapt` |
| `org.jetbrains.kotlin.plugin.spring` | `build.gradle.kts:17,42` | `kotlin` | `kotlin-spring` ◊ |
| `org.jetbrains.kotlin.plugin.jpa` | `apps/*/build.gradle.kts:2`, `modules/jpa/build.gradle.kts:2` | `kotlin` | `kotlin-jpa` ◊ |
| `org.springframework.boot` | `build.gradle.kts:18,43` | `springBoot` | `springBoot` ◊ |
| `io.spring.dependency-management` | `build.gradle.kts:19,44` | `springDependencyManagement` | `spring-dependencyManagement` ◊ |
| `org.jlleitschuh.gradle.ktlint` | `build.gradle.kts:20,46` | `ktlintGradle` | `ktlint` |
| `jacoco`, `java-test-fixtures` | `build.gradle.kts:45`; `modules/{jpa,kafka,redis}/build.gradle.kts` | core Gradle, no version | no entry |

### Plugin artifacts as `[libraries]` (for a `build-logic` build's `dependencies {}`; coordinates from §3.3)

| Coordinate | Version from | Key ("Suffix plugin libraries with -plugin") |
| --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` (jvm and kapt) | `kotlin` | `kotlin-plugin` |
| `org.jetbrains.kotlin:kotlin-allopen` (plugin.spring) | `kotlin` | `kotlin-allopen-plugin` |
| `org.jetbrains.kotlin:kotlin-noarg` (plugin.jpa) | `kotlin` | `kotlin-noarg-plugin` |
| `org.springframework.boot:spring-boot-gradle-plugin` | `springBoot` | `springBoot-plugin` ◊ |
| `io.spring.gradle:dependency-management-plugin` | `springDependencyManagement` | `spring-dependencyManagement-plugin` ◊ |
| `org.jlleitschuh.gradle:ktlint-gradle` (Plugin Portal only) | `ktlintGradle` | `ktlint-plugin` |

### `[libraries]`, versioned today

| Coordinate | Declared at | Key | BOM-managed |
| --- | --- | --- | --- |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | `apps/commerce-api/build.gradle.kts:25` | `springdoc-openapiStarterWebmvcUi` | No |
| `com.ninja-squad:springmockk` | `build.gradle.kts:69` | `springmockk` ◊ | No |
| `org.mockito:mockito-core` | `build.gradle.kts:70` | `mockito-core` ◊ | **Yes** (5.23.0) |
| `org.mockito.kotlin:mockito-kotlin` | `build.gradle.kts:71` | `mockito-kotlin` | No |
| `org.instancio:instancio-junit` | `build.gradle.kts:72` | `instancio-junit` | No |
| `org.instancio:instancio-kotlin` | `build.gradle.kts:73` | `instancio-kotlin` | No |
| `com.tngtech.archunit:archunit-junit6` | `apps/commerce-api/build.gradle.kts:41` | `archunit-junit6` | No |
| `com.github.maricn:logback-slack-appender` | `supports/logging/build.gradle.kts:9` | `maricn-logbackSlackAppender` ◊ | No |
| `io.github.oshai:kotlin-logging-jvm` | `supports/logging/build.gradle.kts:11` | `oshai-kotlinLoggingJvm` ◊ | No |

### `[libraries]`, versionless today (all BOM-managed; TOML form `{ module = "g:a" }`, §2.3)

| Coordinate | Declared at | Key |
| --- | --- | --- |
| `org.springframework.boot:spring-boot-dependencies` (the BOM; only if declared as `platform(libs.…)`, §2.4) | today via `BOM_COORDINATES` in the Boot plugin | `springBootDependencies` |
| `org.springframework.boot:spring-boot-starter` | `build.gradle.kts:60` | `springBootStarter` |
| `org.springframework.boot:spring-boot-starter-validation` | `build.gradle.kts:57`; `apps/commerce-api/build.gradle.kts:23` | `springBootStarterValidation` |
| `org.springframework.boot:spring-boot-starter-test` | `build.gradle.kts:67` | `springBootStarterTest` |
| `org.springframework.boot:spring-boot-testcontainers` | `build.gradle.kts:75` | `springBootTestcontainers` |
| `org.springframework.boot:spring-boot-starter-webmvc` | `apps/commerce-api/build.gradle.kts:21`; `apps/commerce-streamer/build.gradle.kts:15` | `springBootStarterWebmvc` |
| `org.springframework.boot:spring-boot-starter-actuator` | `apps/commerce-api/build.gradle.kts:24`; `apps/commerce-streamer/build.gradle.kts:16`; `supports/logging/build.gradle.kts:3`; `supports/monitoring/build.gradle.kts:2` | `springBootStarterActuator` |
| `org.springframework.boot:spring-boot-starter-security` | `apps/commerce-api/build.gradle.kts:28` | `springBootStarterSecurity` |
| `org.springframework.boot:spring-boot-starter-security-test` | `apps/commerce-api/build.gradle.kts:29` | `springBootStarterSecurityTest` |
| `org.springframework.boot:spring-boot-starter-webmvc-test` | `apps/commerce-api/build.gradle.kts:30` | `springBootStarterWebmvcTest` |
| `org.springframework.boot:spring-boot-starter-data-jpa-test` | `apps/commerce-api/build.gradle.kts:31` | `springBootStarterDataJpaTest` |
| `org.springframework.boot:spring-boot-starter-batch-jdbc` | `apps/commerce-batch/build.gradle.kts:14` | `springBootStarterBatchJdbc` |
| `org.springframework.boot:spring-boot-starter-batch-jdbc-test` | `apps/commerce-batch/build.gradle.kts:15` | `springBootStarterBatchJdbcTest` |
| `org.springframework.boot:spring-boot-starter-data-jpa` | `modules/jpa/build.gradle.kts:15,24` | `springBootStarterDataJpa` |
| `org.springframework.boot:spring-boot-starter-kafka` | `modules/kafka/build.gradle.kts:6` | `springBootStarterKafka` |
| `org.springframework.boot:spring-boot-starter-kafka-test` | `modules/kafka/build.gradle.kts:8` | `springBootStarterKafkaTest` |
| `org.springframework.boot:spring-boot-starter-data-redis` | `modules/redis/build.gradle.kts:6` | `springBootStarterDataRedis` |
| `org.springframework.boot:spring-boot-jackson` | `supports/jackson/build.gradle.kts:4` | `springBootJackson` |
| `org.springframework.boot:spring-boot-micrometer-tracing-brave` | `supports/logging/build.gradle.kts:7` | `springBootMicrometerTracingBrave` |
| `org.springframework:spring-web` | `supports/jackson/build.gradle.kts:3` | `springWeb` |
| `org.jetbrains.kotlin:kotlin-reflect` | `build.gradle.kts:58` | `kotlin-reflect` |
| `org.jetbrains.kotlin:kotlin-test-junit5` | `build.gradle.kts:68` | `kotlin-testJunit5` |
| `tools.jackson.module:jackson-module-kotlin` | `build.gradle.kts:62`; `supports/jackson/build.gradle.kts:6` | `jackson-moduleKotlin` ◊ |
| `org.junit.platform:junit-platform-launcher` | `build.gradle.kts:64` | `junit-platformLauncher` |
| `com.mysql:mysql-connector-j` | `build.gradle.kts:66`; `modules/jpa/build.gradle.kts:20` | `mysql-connectorJ` |
| `org.testcontainers:testcontainers` | `build.gradle.kts:76`; `modules/redis/build.gradle.kts:8` | `testcontainers` |
| `org.testcontainers:testcontainers-junit-jupiter` | `build.gradle.kts:77` | `testcontainers-junitJupiter` |
| `org.testcontainers:testcontainers-mysql` | `modules/jpa/build.gradle.kts:22,25` | `testcontainers-mysql` |
| `org.testcontainers:testcontainers-kafka` | `modules/kafka/build.gradle.kts:9,11` | `testcontainers-kafka` |
| `com.querydsl:querydsl-apt` (classifier `jakarta`; use site needs `variantOf`, §2.6) | `apps/commerce-api/build.gradle.kts:34`; `apps/commerce-batch/build.gradle.kts:18`; `apps/commerce-streamer/build.gradle.kts:19`; `modules/jpa/build.gradle.kts:18` | `querydsl-apt` |
| `com.querydsl:querydsl-jpa` (classifier `jakarta`) | `modules/jpa/build.gradle.kts:17` | `querydsl-jpa` |
| `io.micrometer:micrometer-registry-prometheus` | `supports/logging/build.gradle.kts:5`; `supports/monitoring/build.gradle.kts:3` | `micrometer-registryPrometheus` |
| `io.micrometer:micrometer-tracing-bridge-brave` | `supports/logging/build.gradle.kts:6` | `micrometer-tracingBridgeBrave` |

The only leaf-and-group pair among these keys is `testcontainers` with `testcontainers-*`. It coexists under the rule in §2.2. The other keys that share a first segment (`mockito-*`, `instancio-*`, `querydsl-*`, `micrometer-*`, `kotlin-*`) are siblings with no leaf of the group's name.

Counts: 13 `[versions]` keys, 7 `[plugins]` entries, 6 plugin-artifact libraries, 9 versioned libraries and 33 versionless coordinates (32 libraries plus the BOM itself).

## Open questions

Primary sources and the measurements above did not settle these.

1. **Runtime effect of the 14 upgraded coordinates outside the test suite.**
   - The Jackson 2 change (2.21.x → 2.22.x) reaches only springdoc's `swagger-core-jakarta` in commerce-api, and no test exercises springdoc (§5.4).
   - JUnit 6.1.3 versus 6.0.3 and Mockito 5.23.0 versus 5.14.0 pass all 429 tests.
   - Whether springdoc's `/v3/api-docs` output changes was not checked.
2. **Whether io.spring.dependency-management 1.1.7 is supported on Gradle 9.** Its docs list Gradle up to 8.x (§4.7). Boot 4.1.1 ships it, Initializr pairs it with Gradle 9.7.1, and this repo's build passes. No dependency-management-plugin source states Gradle 9 support.
3. **Moving all seven plugins into `build-logic`.** The trial moved only the Kotlin jvm plugin, in one module (§5.6). Untried:
   - kapt, allopen/noarg (`allOpen {}`), Boot, dependency-management, ktlint (Plugin Portal only) and JaCoCo;
   - whether removing `apply(plugin = …)` from the root while some modules still get plugins from it causes "already on the classpath with an unknown version" errors ([gh-18236]);
   - whether the root's own `kotlin("jvm")`/`kotlin("kapt")` (`build.gradle.kts:15-16`) matter once modules get Kotlin from `build-logic`.
4. **kotlin-dsl (Kotlin 2.4.0) with KGP 2.3.21 on build-logic's classpath.** No Gradle or Kotlin doc covers this pairing (§3.6). The trial printed no warning, which is an observation, not a documented guarantee.
5. **The full Isolated Projects violation list.** Gradle stops at the first violation (`build.gradle.kts:30`), so the measured run does not enumerate the others (§5.6).
6. **Gradle issue 20545** (`eachPlugin` with multi-variant plugins such as KGP). The issue is open. Whether today's `eachPlugin` setup selects a different KGP variant than a versioned `plugins {}` or catalog `alias()` would was not compared.
7. **Catalog keys.** The naming page admits several keys per coordinate (§2.2). The ◊ rows in the repo inventory have more than one rule-consistent key.
8. **Upstream alignment.** The upstream template has the same root-script layout and no catalog (repo-state paragraph). No upstream file read here says whether upstream intends to add a catalog or convention plugins.

<!-- References -->
[bp]: https://docs.gradle.org/9.7.1/userguide/best_practices.html
[bp-index]: https://docs.gradle.org/9.7.1/userguide/best_practices_index.html
[bp-general]: https://docs.gradle.org/9.7.1/userguide/best_practices_general.html
[bp-struct]: https://docs.gradle.org/9.7.1/userguide/best_practices_structuring_builds.html
[bp-deps]: https://docs.gradle.org/9.7.1/userguide/best_practices_dependencies.html
[bp-tasks]: https://docs.gradle.org/9.7.1/userguide/best_practices_tasks.html
[bp-perf]: https://docs.gradle.org/9.7.1/userguide/best_practices_performance.html
[bp-sec]: https://docs.gradle.org/9.7.1/userguide/best_practices_security.html
[bp-test]: https://docs.gradle.org/9.7.1/userguide/best_practices_testing.html
[bp98-general]: https://docs.gradle.org/9.8.0/userguide/best_practices_general.html#obtain_loggers_via_logging_get_logger
[bp98-tasks]: https://docs.gradle.org/9.8.0/userguide/best_practices_tasks.html#favor_collection_properties
[bp98-sec]: https://docs.gradle.org/9.8.0/userguide/best_practices_security.html#build-published-artifacts-securely
[gradle-versions]: https://services.gradle.org/versions/all
[vc]: https://docs.gradle.org/9.7.1/userguide/version_catalogs.html
[vc-problems]: https://docs.gradle.org/9.7.1/userguide/how_to_fix_version_catalog_problems.html
[catalog-platform]: https://docs.gradle.org/9.7.1/userguide/centralizing_catalog_platform.html
[platforms]: https://docs.gradle.org/9.7.1/userguide/platforms.html
[dep-versions]: https://docs.gradle.org/9.7.1/userguide/dependency_versions.html
[dep-constraints]: https://docs.gradle.org/9.7.1/userguide/dependency_constraints.html
[plugins-int]: https://docs.gradle.org/9.7.1/userguide/plugins_intermediate.html
[pds-javadoc]: https://docs.gradle.org/9.7.1/javadoc/org/gradle/plugin/use/PluginDependenciesSpec.html
[vcat-javadoc]: https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/artifacts/VersionCatalog.html
[vcats-javadoc]: https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/artifacts/VersionCatalogsExtension.html
[vcb-javadoc]: https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/initialization/dsl/VersionCatalogBuilder.html
[dh-javadoc]: https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/artifacts/dsl/DependencyHandler.html
[upgrading9]: https://docs.gradle.org/9.7.1/userguide/upgrading_version_9.html
[upgrading-major9]: https://docs.gradle.org/9.7.1/userguide/upgrading_major_version_9.html#removal_of_libraries_and_bundles_from_version_catalogs_in_the_plugins_block_in_kotlin_dsl
[central-repos]: https://docs.gradle.org/9.7.1/userguide/centralizing_repositories.html
[repos-mode]: https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/initialization/resolve/RepositoriesMode.html
[filtering]: https://docs.gradle.org/9.7.1/userguide/filtering_repository_content.html
[sharing]: https://docs.gradle.org/9.7.1/userguide/sharing_build_logic_between_subprojects.html
[composite]: https://docs.gradle.org/9.7.1/userguide/composite_builds.html
[precompiled]: https://docs.gradle.org/9.7.1/userguide/implementing_gradle_plugins_precompiled.html
[convention-impl]: https://docs.gradle.org/9.7.1/userguide/implementing_gradle_plugins_convention.html
[kotlin-dsl]: https://docs.gradle.org/9.7.1/userguide/kotlin_dsl.html
[compat]: https://docs.gradle.org/9.7.1/userguide/compatibility.html#kotlin
[isolated]: https://docs.gradle.org/9.7.1/userguide/isolated_projects.html
[cc]: https://docs.gradle.org/9.7.1/userguide/configuration_cache.html
[cc-req]: https://docs.gradle.org/9.7.1/userguide/configuration_cache_requirements.html
[tca]: https://docs.gradle.org/9.7.1/userguide/task_configuration_avoidance.html
[jacoco]: https://docs.gradle.org/9.7.1/userguide/jacoco_plugin.html#sec:jacoco_getting_started
[sample-971]: https://docs.gradle.org/9.7.1/samples/sample_convention_plugins.html
[sample-94]: https://docs.gradle.org/9.4.0/samples/sample_convention_plugins.html
[sample-94-bl]: https://docs.gradle.org/9.4.0/samples/sample_sharing_convention_plugins_with_build_logic.html
[gh-15383]: https://github.com/gradle/gradle/issues/15383
[gh-15383-wa]: https://github.com/gradle/gradle/issues/15383#issuecomment-779893192
[gh-18236]: https://github.com/gradle/gradle/issues/18236
[gh-20545]: https://github.com/gradle/gradle/issues/20545
[gh-26048]: https://github.com/gradle/gradle/issues/26048
[gh-17117]: https://github.com/gradle/gradle/issues/17117
[gh-39350]: https://github.com/gradle/gradle/issues/39350
[embedded-kotlin-src]: https://github.com/gradle/gradle/blob/v9.7.1/platforms/core-configuration/kotlin-dsl-plugins/src/main/kotlin/org/gradle/kotlin/dsl/plugins/embedded/EmbeddedKotlinPlugin.kt#L76-L90
[kotlin-dsl-compiler-src]: https://github.com/gradle/gradle/blob/v9.7.1/platforms/core-configuration/kotlin-dsl-plugins/src/main/kotlin/org/gradle/kotlin/dsl/plugins/dsl/KotlinDslCompilerPlugins.kt#L54-L55
[kt-bp]: https://kotlinlang.org/docs/gradle-best-practices.html
[kgp]: https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin
[m-kjvm]: https://plugins.gradle.org/m2/org/jetbrains/kotlin/jvm/org.jetbrains.kotlin.jvm.gradle.plugin/2.3.21/org.jetbrains.kotlin.jvm.gradle.plugin-2.3.21.pom
[m-kapt]: https://plugins.gradle.org/m2/org/jetbrains/kotlin/kapt/org.jetbrains.kotlin.kapt.gradle.plugin/2.3.21/org.jetbrains.kotlin.kapt.gradle.plugin-2.3.21.pom
[m-kspring]: https://plugins.gradle.org/m2/org/jetbrains/kotlin/plugin/spring/org.jetbrains.kotlin.plugin.spring.gradle.plugin/2.3.21/org.jetbrains.kotlin.plugin.spring.gradle.plugin-2.3.21.pom
[m-kjpa]: https://plugins.gradle.org/m2/org/jetbrains/kotlin/plugin/jpa/org.jetbrains.kotlin.plugin.jpa.gradle.plugin/2.3.21/org.jetbrains.kotlin.plugin.jpa.gradle.plugin-2.3.21.pom
[m-boot]: https://plugins.gradle.org/m2/org/springframework/boot/org.springframework.boot.gradle.plugin/4.1.1/org.springframework.boot.gradle.plugin-4.1.1.pom
[m-dm]: https://plugins.gradle.org/m2/io/spring/dependency-management/io.spring.dependency-management.gradle.plugin/1.1.7/io.spring.dependency-management.gradle.plugin-1.1.7.pom
[m-ktlint]: https://plugins.gradle.org/m2/org/jlleitschuh/gradle/ktlint/org.jlleitschuh.gradle.ktlint.gradle.plugin/14.2.0/org.jlleitschuh.gradle.ktlint.gradle.plugin-14.2.0.pom
[m-kdsl]: https://plugins.gradle.org/m2/org/gradle/kotlin/kotlin-dsl/org.gradle.kotlin.kotlin-dsl.gradle.plugin/6.7.3/org.gradle.kotlin.kotlin-dsl.gradle.plugin-6.7.3.pom
[kce-pom]: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.21/kotlin-compiler-embeddable-2.3.21.pom
[bom411]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom
[boot-deps]: https://docs.spring.io/spring-boot/4.1/gradle-plugin/managing-dependencies.html
[boot-reacting]: https://docs.spring.io/spring-boot/4.1/gradle-plugin/reacting.html
[boot-gs]: https://docs.spring.io/spring-boot/4.1/gradle-plugin/getting-started.html
[boot-packaging]: https://docs.spring.io/spring-boot/4.1/gradle-plugin/packaging.html
[boot-howto-build]: https://docs.spring.io/spring-boot/4.1/how-to/build.html#howto.build.use-a-spring-boot-application-as-dependency
[boot-props]: https://docs.spring.io/spring-boot/4.1/appendix/dependency-versions/properties.html
[sbp-src]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/build-plugin/spring-boot-gradle-plugin/src/main/java/org/springframework/boot/gradle/plugin/SpringBootPlugin.java
[kotlin-action-src]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/build-plugin/spring-boot-gradle-plugin/src/main/java/org/springframework/boot/gradle/plugin/KotlinPluginAction.java
[resolve-main-src]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/build-plugin/spring-boot-gradle-plugin/src/main/java/org/springframework/boot/gradle/plugin/ResolveMainClassName.java
[spring-guide-mm]: https://spring.io/guides/gs/multi-module
[init-zip]: https://start.spring.io/starter.zip?type=gradle-project-kotlin&language=kotlin&bootVersion=4.1.1&dependencies=web
[dmp]: https://docs.spring.io/dependency-management-plugin/docs/current/reference/html/
[dmp-211]: https://github.com/spring-gradle-plugins/dependency-management-plugin/issues/211#issuecomment-387362326
[dmp-330]: https://github.com/spring-gradle-plugins/dependency-management-plugin/issues/330
[dmp-331]: https://github.com/spring-gradle-plugins/dependency-management-plugin/issues/331
[dmp-369]: https://github.com/spring-gradle-plugins/dependency-management-plugin/issues/369#issuecomment-2177862310
[dmp-398]: https://github.com/spring-gradle-plugins/dependency-management-plugin/issues/398#issuecomment-2450529691
[dmp-406]: https://github.com/spring-gradle-plugins/dependency-management-plugin/issues/406#issuecomment-2915148771
[dmp-407]: https://github.com/spring-gradle-plugins/dependency-management-plugin/issues/407
[boot-11711]: https://github.com/spring-projects/spring-boot/issues/11711#issuecomment-361945301
[boot-21723]: https://github.com/spring-projects/spring-boot/issues/21723#issuecomment-639679756
[boot-26840]: https://github.com/spring-projects/spring-boot/issues/26840#issuecomment-857865885
[boot-41432]: https://github.com/spring-projects/spring-boot/issues/41432#issuecomment-2219660506
[init-1414]: https://github.com/spring-io/initializr/issues/1414#issuecomment-1551788820
