package com.loopers

import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
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

    // Cycles are checked inside one layer at a time. application.brand reading domain.product while
    // domain.product refers to domain.brand is a use case looking down at two aggregates, not a loop (design 5.16).
    @ArchTest
    val domainSlicesAreFreeOfCycles: ArchRule = slicesOf("domain").shouldBeFreeOfCycles()

    @ArchTest
    val applicationSlicesAreFreeOfCycles: ArchRule = slicesOf("application").shouldBeFreeOfCycles()

    @ArchTest
    val infrastructureSlicesAreFreeOfCycles: ArchRule = slicesOf("infrastructure").shouldBeFreeOfCycles()

    // The first alternative absorbs the API version segment and the second the technology segment (api, scheduler, ...),
    // so the feature stays the slice. api.v* comes first because alternatives are tried in order.
    @ArchTest
    val interfacesSlicesAreFreeOfCycles: ArchRule = slicesOf("interfaces.[api.v*|*]").shouldBeFreeOfCycles()

    @ArchTest
    val domainSlicesOnlyReadEachOther: ArchRule = slicesOf("domain")
        .should(onlyReadOtherSlices())
        .because("an aggregate must not change another aggregate's state, even through a JPA association (design 5.1)")
}

/** Slices of one layer: the package segment right after [layerPattern] names the feature. */
private fun slicesOf(layerPattern: String) = slices().matching("$ROOT.$layerPattern.(*)..")

private fun GivenSlices.shouldBeFreeOfCycles(): ArchRule =
    should().beFreeOfCycles().because("peer features in one layer must depend on each other in one direction only")

/**
 * A call from one slice into another may only read: a getter, a method of an enum, or a method of a record.
 * Kotlin has no records, so a data class whose properties are all `val` counts as one.
 * Constructor calls are not method calls, so a slice may still create another slice's values and exceptions.
 * Calls to classes outside every slice (the layer root, the JDK) are not checked.
 */
private fun onlyReadOtherSlices() =
    object : ArchCondition<Slice>("only read classes of other slices") {
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

private fun JavaMethodCall.onlyReads(): Boolean =
    target.isGetter() || targetOwner.isEnum || targetOwner.isRecord || targetOwner.isImmutableDataClass()

private fun MethodCallTarget.isGetter(): Boolean =
    GETTER_NAME.matches(name) && rawParameterTypes.isEmpty() && rawReturnType.name != "void"

private fun JavaClass.isImmutableDataClass(): Boolean {
    val type = reflect()
    // Kotlin reflection only reads real classes (metadata kind 1); file facades and synthetic classes throw.
    if (type.getAnnotation(Metadata::class.java)?.kind != 1) return false
    return type.kotlin.isData && type.kotlin.memberProperties.none { it is KMutableProperty<*> }
}
