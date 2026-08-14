package com.rohittp.debuginput.gradle

import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * The compiler plugin's CLI option names and the computation of their values.
 *
 * Nothing here touches Gradle's project model, so the three decisions that matter —
 * which compilations carry the plugin, which of those skip the transform, and which
 * dependency descriptor functions a module aggregates — can be tested without running
 * a build. That is the point: `:app` hand-wires the same options through
 * `freeCompilerArgs` (ADR-0007), so two things compute these strings and only this one
 * is cheap to pin down.
 *
 * Option names are fixed by the shared contract in
 * `docs/superpowers/plans/2026-08-14-debug-input-m1.md`.
 */
internal object DebugInputOptions {

    const val ENABLED = "enabled"
    const val MODULE = "module"
    const val MANIFEST_OUT = "manifestOut"
    const val DEPENDENCY_DESCRIPTORS = "dependencyDescriptors"

    /** `KotlinPlatformType` names, kept as plain strings so this file needs no KGP model. */
    const val ANDROID_JVM = "androidJvm"
    const val NATIVE = "native"

    /**
     * Compilation-name suffixes that mark a test compilation. `androidTarget()` produces
     * `releaseUnitTest` and `debugAndroidTest`, AGP's KMP library plugin produces
     * `unitTest` and `instrumentedTest`, and plain multiplatform targets produce `test`,
     * hence the case-insensitive comparison and the bare `Test` fallback.
     */
    private val TEST_SUFFIXES = listOf("UnitTest", "AndroidTest", "InstrumentedTest", "Test")

    /**
     * Whether the compiler plugin is added to this compilation at all.
     *
     * Only `androidJvm` and `native`, because those are the only platforms
     * `debug-input-runtime` publishes for. Test compilations are left alone: the
     * descriptor function's name is derived from [MODULE] alone, so instrumenting a
     * module's main *and* test compilations would put the same function into one
     * classpath twice. Metadata compilations (`common`) run no IR at all.
     */
    fun isInstrumented(platformType: String, compilationName: String): Boolean =
        (platformType == ANDROID_JVM || platformType == NATIVE) &&
            !isTestCompilation(compilationName)

    /**
     * The `enabled` option — false only for Android release variants (ADR-0002).
     *
     * Kotlin/Native has no build type: one klib feeds both `linkDebugFramework…` and
     * `linkReleaseFramework…`, so every native compilation is enabled and inertness is
     * left to `DebugInputRegistry` at runtime.
     */
    fun isTransformEnabled(platformType: String, compilationName: String): Boolean =
        platformType != ANDROID_JVM || !isAndroidReleaseVariant(compilationName)

    /**
     * The full option list for one compilation. [manifestOut] and [dependencyManifests]
     * are passed in already resolved so that this stays a function of plain values.
     */
    fun optionsFor(
        platformType: String,
        compilationName: String,
        projectPath: String,
        manifestOut: String,
        dependencyManifests: List<String>,
    ): List<SubpluginOption> {
        val enabled = isTransformEnabled(platformType, compilationName)
        return buildList {
            add(SubpluginOption(ENABLED, enabled.toString()))
            add(SubpluginOption(MODULE, projectPath))
            add(SubpluginOption(MANIFEST_OUT, manifestOut))
            // A disabled compilation emits no descriptor function, so it has nothing to
            // aggregate its dependencies into.
            if (enabled) {
                descriptorFunctionsOf(dependencyManifests).forEach {
                    add(SubpluginOption(DEPENDENCY_DESCRIPTORS, it))
                }
            }
        }
    }

    /**
     * The `function` field of each descriptor manifest, de-duplicated and sorted so the
     * option order does not depend on artifact resolution order.
     *
     * A manifest that is empty, truncated or not JSON at all is skipped rather than
     * fatal. The cost of skipping is one module's rows missing from the page; the cost
     * of throwing is the consumer's build failing over a generated file they never
     * wrote.
     */
    fun descriptorFunctionsOf(manifests: List<String>): List<String> =
        manifests.mapNotNull(::functionOf).distinct().sorted()

    private fun functionOf(manifest: String): String? {
        val parsed = try {
            JsonSlurper().parseText(manifest)
        } catch (ignored: Exception) {
            return null
        }
        return ((parsed as? Map<*, *>)?.get("function") as? String)?.takeIf { it.isNotBlank() }
    }

    private fun isTestCompilation(compilationName: String): Boolean =
        TEST_SUFFIXES.any { compilationName.endsWith(it, ignoreCase = true) }

    /**
     * Android variant names are `<flavour…><BuildType>`, with the build type capitalised
     * only when a flavour precedes it, plus a test suffix on test compilations.
     * `KotlinCompilation` exposes nothing that names the build type, so the name is the
     * only signal available short of depending on AGP's variant API. A consumer with a
     * third build type therefore gets the transform: erring towards instrumented is a
     * bigger APK, erring the other way is a page missing rows in debug.
     */
    private fun isAndroidReleaseVariant(compilationName: String): Boolean {
        val variant = TEST_SUFFIXES.fold(compilationName) { name, suffix -> name.removeSuffix(suffix) }
        return variant == "release" || variant.endsWith("Release")
    }
}
