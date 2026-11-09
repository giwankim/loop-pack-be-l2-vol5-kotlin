package com.loopers

import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.Slice
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import com.tngtech.archunit.library.dependencies.syntax.GivenSlices
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties

private const val ROOT = "com.loopers"
private val GETTER_NAME = Regex("(get|is)[A-Z].*")

@AnalyzeClasses(
    packagesOf = [CommerceApiApplication::class],
    importOptions = [
        ImportOption.DoNotIncludeTests::class,
        ImportOption.DoNotIncludeGradleTestFixtures::class,
        ImportOption.DoNotIncludeJars::class,
    ],
)
class HexagonalArchitectureTest {
    // splearn's three layers, with its single adapter layer split in five so that the web, persistence, integration
    // (outbound HTTP, ADR 0021), scheduling (ADR 0035) and startup (ADR 0036) adapters cannot reach each other. ApiControllerAdvice sits at the
    // adapter root as in splearn, but it only answers web requests, so that one package belongs to the web adapter.
    @ArchTest
    val dependenciesPointInward: ArchRule = layeredArchitecture()
        .consideringAllDependencies()
        .layer("domain").definedBy("$ROOT.domain..")
        .layer("application").definedBy("$ROOT.application..")
        .layer("adapter.webapi").definedBy("$ROOT.adapter", "$ROOT.adapter.webapi..")
        .layer("adapter.persistence").definedBy("$ROOT.adapter.persistence..")
        .layer("adapter.integration").definedBy("$ROOT.adapter.integration..")
        .layer("adapter.scheduling").definedBy("$ROOT.adapter.scheduling..")
        .layer("adapter.startup").definedBy("$ROOT.adapter.startup..")
        .layer("support").definedBy("$ROOT.support..")
        .whereLayer("domain")
        .mayOnlyBeAccessedByLayers("application", "adapter.webapi", "adapter.persistence", "adapter.integration")
        .whereLayer("application")
        .mayOnlyBeAccessedByLayers(
            "adapter.webapi",
            "adapter.persistence",
            "adapter.integration",
            "adapter.scheduling",
            "adapter.startup",
        )
        .whereLayer("adapter.webapi").mayNotBeAccessedByAnyLayer()
        .whereLayer("adapter.persistence").mayNotBeAccessedByAnyLayer()
        .whereLayer("adapter.integration").mayNotBeAccessedByAnyLayer()
        .whereLayer("adapter.scheduling").mayNotBeAccessedByAnyLayer()
        .whereLayer("adapter.startup").mayNotBeAccessedByAnyLayer()
        .ensureAllClassesAreContainedInArchitectureIgnoring(ROOT)

    // A trigger starts what the application offers and decides nothing, so of this code base it sees only the provided ports. The layer
    // rule above can't say this: to it, OrderEventRelay itself and a required port are "the application layer" too. The relay's
    // schedule and 누락 복구's startup runner are both triggers.
    @ArchTest
    val schedulingReachesOnlyProvidedPorts: ArchRule = triggerReachesOnlyProvidedPorts("adapter.scheduling", "ADR 0035")

    @ArchTest
    val startupReachesOnlyProvidedPorts: ArchRule = triggerReachesOnlyProvidedPorts("adapter.startup", "ADR 0036")

    // Cycles are checked inside one layer at a time. adapter.persistence.product joining domain.like to sort by
    // likes while application.like calls application.product is two layers each looking one way, not a loop
    // (design 5.16).
    @ArchTest
    val domainSlicesAreFreeOfCycles: ArchRule = slicesOf("domain").shouldBeFreeOfCycles()

    @ArchTest
    val applicationSlicesAreFreeOfCycles: ArchRule = slicesOf("application").shouldBeFreeOfCycles()

    // v* absorbs the API version segment, so the feature stays the slice.
    @ArchTest
    val webApiSlicesAreFreeOfCycles: ArchRule = slicesOf("adapter.webapi.v*").shouldBeFreeOfCycles()

    @ArchTest
    val persistenceSlicesAreFreeOfCycles: ArchRule = slicesOf("adapter.persistence").shouldBeFreeOfCycles()

    @ArchTest
    val integrationSlicesAreFreeOfCycles: ArchRule = slicesOf("adapter.integration").shouldBeFreeOfCycles()

    @ArchTest
    val domainSlicesOnlyReadEachOther: ArchRule = slicesOf("domain")
        .should(onlyReadOtherSlices())
        .because("an aggregate must not change another aggregate's state, even through a JPA association (design 5.1)")
}

/** The classes in [layerPattern] depend on nothing of this code base but their own layer and the provided ports. */
private fun triggerReachesOnlyProvidedPorts(layerPattern: String, adr: String): ArchRule {
    return classes()
        .that().resideInAPackage("$ROOT.$layerPattern..")
        .should().onlyDependOnClassesThat(
            resideOutsideOfPackage("$ROOT..")
                .or(resideInAnyPackage("$ROOT.$layerPattern..", "$ROOT.application..provided..")),
        )
        .because("a trigger calls a provided port and nothing else ($adr)")
}

/** Slices of one layer: the package segment right after [layerPattern] names the feature. */
private fun slicesOf(layerPattern: String): GivenSlices {
    return slices().matching("$ROOT.$layerPattern.(*)..")
}

private fun GivenSlices.shouldBeFreeOfCycles(): ArchRule {
    return should().beFreeOfCycles().because("peer features in one layer must depend on each other in one direction only")
}

/**
 * A call from one slice into another may only read: a getter, a method of an enum, or a method of a record.
 * Kotlin has no records, so a data class whose properties are all `val` counts as one.
 * Constructor calls are not method calls, so a slice may still create another slice's values and exceptions.
 * Calls to classes outside every slice (the layer root, the JDK) are not checked.
 */
private fun onlyReadOtherSlices(): ArchCondition<Slice> {
    return object : ArchCondition<Slice>("only read classes of other slices") {
        private val classesInAnySlice = mutableSetOf<JavaClass>()

        override fun init(allSlices: Collection<Slice>) {
            allSlices.forEach(classesInAnySlice::addAll)
        }

        override fun check(slice: Slice, events: ConditionEvents) {
            slice.flatMap { it.methodCallsFromSelf }
                .filter { it.targetOwner !in slice && it.targetOwner in classesInAnySlice }
                .filterNot { it.onlyReads() }
                .forEach { events.add(SimpleConditionEvent.violated(it, it.description)) }
        }
    }
}

private fun JavaMethodCall.onlyReads(): Boolean {
    return target.isGetter() || targetOwner.isEnum || targetOwner.isRecord || targetOwner.isImmutableDataClass()
}

private fun MethodCallTarget.isGetter(): Boolean {
    return GETTER_NAME.matches(name) && rawParameterTypes.isEmpty() && rawReturnType.name != "void"
}

private fun JavaClass.isImmutableDataClass(): Boolean {
    val type = reflect()
    // Kotlin reflection only reads real classes (metadata kind 1); file facades and synthetic classes throw.
    if (type.getAnnotation(Metadata::class.java)?.kind != 1) return false
    return type.kotlin.isData && type.kotlin.memberProperties.none { it is KMutableProperty<*> }
}
