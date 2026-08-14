package com.rohittp.debuginput.gradle

import com.rohittp.debuginput.gradle.DebugInputOptions.ANDROID_JVM
import com.rohittp.debuginput.gradle.DebugInputOptions.DEPENDENCY_DESCRIPTORS
import com.rohittp.debuginput.gradle.DebugInputOptions.ENABLED
import com.rohittp.debuginput.gradle.DebugInputOptions.MANIFEST_OUT
import com.rohittp.debuginput.gradle.DebugInputOptions.MODULE
import com.rohittp.debuginput.gradle.DebugInputOptions.NATIVE
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The option computation is the one piece of this module that a real build never
 * exercises, because `:app` hand-wires the same strings itself (ADR-0007).
 */
class DebugInputOptionsTest {

    @Test
    fun `android release skips the transform`() {
        assertFalse(DebugInputOptions.isTransformEnabled(ANDROID_JVM, "release"))
    }

    @Test
    fun `a flavoured android release skips the transform`() {
        assertFalse(DebugInputOptions.isTransformEnabled(ANDROID_JVM, "freeRelease"))
        assertFalse(DebugInputOptions.isTransformEnabled(ANDROID_JVM, "paidProdRelease"))
    }

    @Test
    fun `android debug runs the transform`() {
        assertTrue(DebugInputOptions.isTransformEnabled(ANDROID_JVM, "debug"))
        assertTrue(DebugInputOptions.isTransformEnabled(ANDROID_JVM, "freeDebug"))
    }

    @Test
    fun `every ios target runs the transform`() {
        // One klib feeds both linkDebugFramework and linkReleaseFramework, so there is no
        // release compilation to skip: inertness is the registry's job at runtime.
        listOf("iosArm64", "iosSimulatorArm64", "iosX64").forEach { target ->
            assertTrue(
                DebugInputOptions.isTransformEnabled(NATIVE, "main"),
                "$target main should be instrumented",
            )
        }
    }

    @Test
    fun `an ios compilation named release still runs the transform`() {
        // A native target's compilations are never named after a build type, but nothing
        // stops a consumer from creating one, and native has no release to skip.
        assertTrue(DebugInputOptions.isTransformEnabled(NATIVE, "release"))
    }

    @Test
    fun `android and native main compilations are instrumented`() {
        assertTrue(DebugInputOptions.isInstrumented(ANDROID_JVM, "debug"))
        assertTrue(DebugInputOptions.isInstrumented(ANDROID_JVM, "release"))
        assertTrue(DebugInputOptions.isInstrumented(NATIVE, "main"))
    }

    @Test
    fun `test compilations are not instrumented`() {
        listOf("test", "releaseUnitTest", "debugAndroidTest", "unitTest", "instrumentedTest")
            .forEach { assertFalse(DebugInputOptions.isInstrumented(ANDROID_JVM, it), it) }
        assertFalse(DebugInputOptions.isInstrumented(NATIVE, "test"))
    }

    @Test
    fun `unsupported platforms are not instrumented`() {
        // debug-input-runtime publishes for androidTarget and the three iOS targets only.
        listOf("jvm", "js", "wasm", "common").forEach {
            assertFalse(DebugInputOptions.isInstrumented(it, "main"), it)
        }
    }

    @Test
    fun `the module option is the gradle project path`() {
        val options = optionsFor(projectPath = ":sample:domain")

        assertEquals(":sample:domain", options.value(MODULE))
    }

    @Test
    fun `an enabled compilation gets every option`() {
        val options = optionsFor(
            compilationName = "debug",
            manifestOut = "/build/debug-input/android/debug/descriptors.json",
            dependencyManifests = listOf(manifest(":domain", "descriptors_domain")),
        )

        assertEquals(
            listOf(
                ENABLED to "true",
                MODULE to ":app",
                MANIFEST_OUT to "/build/debug-input/android/debug/descriptors.json",
                DEPENDENCY_DESCRIPTORS to "com.rohittp.debuginput.generated.descriptors_domain",
            ),
            options.map { it.key to it.value },
        )
    }

    @Test
    fun `a disabled compilation aggregates nothing`() {
        val options = optionsFor(
            compilationName = "release",
            dependencyManifests = listOf(manifest(":domain", "descriptors_domain")),
        )

        assertEquals("false", options.value(ENABLED))
        assertEquals(emptyList(), options.values(DEPENDENCY_DESCRIPTORS))
    }

    @Test
    fun `one option is emitted per dependency descriptor function`() {
        val options = optionsFor(
            dependencyManifests = listOf(
                manifest(":domain", "descriptors_domain"),
                manifest(":sample:domain", "descriptors_sample_domain"),
            ),
        )

        assertEquals(
            listOf(
                "com.rohittp.debuginput.generated.descriptors_domain",
                "com.rohittp.debuginput.generated.descriptors_sample_domain",
            ),
            options.values(DEPENDENCY_DESCRIPTORS),
        )
    }

    @Test
    fun `descriptor functions are read out of the manifest json`() {
        val manifests = listOf(
            """
            {
              "module": ":domain",
              "function": "com.rohittp.debuginput.generated.descriptors_domain",
              "inputs": [{ "id": "com.app.physics.speed", "typeKey": "kotlin.Int" }]
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("com.rohittp.debuginput.generated.descriptors_domain"),
            DebugInputOptions.descriptorFunctionsOf(manifests),
        )
    }

    @Test
    fun `descriptor functions are de-duplicated and sorted`() {
        // A module's compilations all report the same function, and a consumer sees
        // whichever of them resolution happens to hand over.
        val manifests = listOf(
            manifest(":shared", "descriptors_shared"),
            manifest(":domain", "descriptors_domain"),
            manifest(":domain", "descriptors_domain"),
        )

        assertEquals(
            listOf(
                "com.rohittp.debuginput.generated.descriptors_domain",
                "com.rohittp.debuginput.generated.descriptors_shared",
            ),
            DebugInputOptions.descriptorFunctionsOf(manifests),
        )
    }

    @Test
    fun `an unreadable manifest is skipped rather than fatal`() {
        val manifests = listOf(
            "",
            "   ",
            "not json at all",
            """{ "module": ":domain", "function":""",
            "[]",
            "null",
            """{ "module": ":domain" }""",
            """{ "module": ":domain", "function": null }""",
            """{ "module": ":domain", "function": "" }""",
            """{ "module": ":domain", "function": 42 }""",
            manifest(":domain", "descriptors_domain"),
        )

        assertEquals(
            listOf("com.rohittp.debuginput.generated.descriptors_domain"),
            DebugInputOptions.descriptorFunctionsOf(manifests),
        )
    }

    @Test
    fun `no manifests means no dependency options`() {
        assertEquals(emptyList(), DebugInputOptions.descriptorFunctionsOf(emptyList()))
    }

    private fun optionsFor(
        platformType: String = ANDROID_JVM,
        compilationName: String = "debug",
        projectPath: String = ":app",
        manifestOut: String = "/build/debug-input/descriptors.json",
        dependencyManifests: List<String> = emptyList(),
    ) = DebugInputOptions.optionsFor(
        platformType = platformType,
        compilationName = compilationName,
        projectPath = projectPath,
        manifestOut = manifestOut,
        dependencyManifests = dependencyManifests,
    )

    private fun manifest(module: String, function: String) = """
        {
          "module": "$module",
          "function": "com.rohittp.debuginput.generated.$function",
          "inputs": []
        }
    """.trimIndent()

    private fun List<SubpluginOption>.value(key: String) = single { it.key == key }.value

    private fun List<SubpluginOption>.values(key: String) = filter { it.key == key }.map { it.value }
}
