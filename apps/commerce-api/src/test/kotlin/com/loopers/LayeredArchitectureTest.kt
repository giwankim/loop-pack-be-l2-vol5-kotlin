package com.loopers

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

private const val ROOT = "com.loopers"

@AnalyzeClasses(
    packagesOf = [CommerceApiApplication::class],
    importOptions = [
        ImportOption.DoNotIncludeTests::class,
        ImportOption.DoNotIncludeGradleTestFixtures::class,
        ImportOption.DoNotIncludeJars::class,
    ],
)
class LayeredArchitectureTest {
    @ArchTest
    val dependenciesPointInward: ArchRule = layeredArchitecture()
        .consideringAllDependencies()
        .layer("domain").definedBy("$ROOT.domain..")
        .layer("application").definedBy("$ROOT.application..")
        .layer("interfaces").definedBy("$ROOT.interfaces..")
        .layer("infrastructure").definedBy("$ROOT.infrastructure..")
        .layer("support").definedBy("$ROOT.support..")
        .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "interfaces", "infrastructure")
        .whereLayer("application").mayOnlyBeAccessedByLayers("interfaces")
        .whereLayer("interfaces").mayNotBeAccessedByAnyLayer()
        .whereLayer("infrastructure").mayNotBeAccessedByAnyLayer()
        .ensureAllClassesAreContainedInArchitectureIgnoring(ROOT)

    @ArchTest
    val businessModulesAreFreeOfCycles: ArchRule = slices()
        // Capture only the module name so domain.order and application.order are one slice.
        // interfaces.* absorbs the technology segment (api, scheduler, ...) so the feature stays the slice.
        .matching("$ROOT.[domain|application|infrastructure|interfaces.*].(*)..")
        .should().beFreeOfCycles()
        .because("feature modules must stay acyclic so any one of them can be extracted on its own")
}
