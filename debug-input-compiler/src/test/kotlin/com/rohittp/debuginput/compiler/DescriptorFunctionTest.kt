package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.DebugInputDescriptor
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The descriptor function, exercised the way the page will: call it and look at what comes
 * back.
 */
class DescriptorFunctionTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `descriptors carry every field the page groups and renders by`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput(docs = "Player speed in m/s")
                val speed = 10

                @DebugInput private val gravity = 9
                """.trimIndent(),
            ),
            SourceFile(
                "Config.kt",
                """
                package com.app

                import com.rohittp.debuginput.DebugInput

                object Config {
                    @DebugInput val timeout = 5
                }
                """.trimIndent(),
            ),
            module = ":domain",
        ).assertSucceeded()

        val descriptors = result.descriptors(":domain").associateBy { it.id }

        assertEquals(
            setOf(
                "com.app.physics.speed",
                "com.app.physics.Physics.kt.gravity",
                "com.app.Config.timeout",
            ),
            descriptors.keys,
        )

        val speed = descriptors.getValue("com.app.physics.speed")
        assertEquals("speed", speed.displayName)
        assertEquals(":domain", speed.module)
        assertEquals("Physics", speed.section)
        assertEquals("", speed.sectionDescription)
        assertEquals("file:com.app.physics/Physics", speed.sectionPageId)
        assertEquals("kotlin.Int", speed.typeKey)
        assertEquals("Player speed in m/s", speed.docs)
        assertEquals(10, speed.default)
        assertEquals(null, speed.enumConstants)

        // No docs argument reaches IR as a missing argument, not as the default expression.
        assertEquals("", descriptors.getValue("com.app.physics.Physics.kt.gravity").docs)
        assertEquals(9, descriptors.getValue("com.app.physics.Physics.kt.gravity").default)

        val timeout = descriptors.getValue("com.app.Config.timeout")
        assertEquals("Config", timeout.section)
        assertEquals("class:com.app.Config", timeout.sectionPageId)
        assertEquals(5, timeout.default)
    }

    @Test
    fun `an explicit section groups properties under one named page`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Model.kt",
                """
                package com.app.ai

                import com.rohittp.debuginput.DebugInput

                @DebugInput(section = "Assistant") val model = "gemini"
                """.trimIndent(),
            ),
            SourceFile(
                "Limits.kt",
                """
                package com.app.ai

                import com.rohittp.debuginput.DebugInput

                @DebugInput(section = "Assistant") val tokenLimit = 1024
                """.trimIndent(),
            ),
            module = ":app",
        ).assertSucceeded()

        val descriptors = result.descriptors(":app")
        assertEquals(setOf("Assistant"), descriptors.mapTo(mutableSetOf()) { it.section })
        assertEquals(
            setOf("custom:Assistant"),
            descriptors.mapNotNullTo(mutableSetOf()) { it.sectionPageId },
        )
    }

    @Test
    fun `ordinary property docs come from KDoc unless the annotation overrides them`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Docs.kt",
                """
                package com.app

                import com.rohittp.debuginput.DebugInput

                /** Player speed in metres per second. */
                @DebugInput val fromKDoc = 10

                /** This text is deliberately overridden. */
                @DebugInput(docs = "explicit wins") val explicit = 20

                @DebugInput val plain = 30
                """.trimIndent(),
            ),
            module = ":domain",
        ).assertSucceeded()

        val docs = result.descriptors(":domain").associate { it.displayName to it.docs }

        assertEquals("Player speed in metres per second.", docs.getValue("fromKDoc"))
        assertEquals("explicit wins", docs.getValue("explicit"))
        assertEquals("", docs.getValue("plain"))
    }

    @Test
    fun `a module with no inputs still has a descriptor function`() {
        val result = compile(
            temp.newFolder(),
            SourceFile("Empty.kt", "package com.app\n\nval unrelated = 1\n"),
            module = ":app",
        ).assertSucceeded()

        assertEquals(emptyList(), result.descriptors(":app"))
    }

    @Test
    fun `an Android release compilation emits no generated package at all`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput val speed = 10
                """.trimIndent(),
            ),
            module = ":app",
            enabled = false,
        ).assertSucceeded()

        // Inertness is provable by absence: no descriptor function means no input id and no
        // docs string in the APK (ADR-0002).
        assertTrue(
            File(result.classesDir, "com/rohittp/debuginput").listFiles().isNullOrEmpty(),
            "generated output leaked into a release compilation",
        )
    }

    @Test
    fun `the descriptor function name is derived from the Gradle project path`() {
        assertEquals("descriptors_sample_domain", descriptorFunctionName(":sample:domain"))
        assertEquals("descriptors_app", descriptorFunctionName(":app"))
        assertEquals("descriptors_root", descriptorFunctionName(":"))
        assertEquals("descriptors_root", descriptorFunctionName(""))
    }

    @Test
    fun `an instance property of an ordinary class gets a null default`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                class Physics {
                    @DebugInput val speed = 10
                }
                """.trimIndent(),
            ),
            module = ":domain",
        ).assertSucceeded()

        // There is no instance to read the field from. The getter rewrite still applies; the
        // page just cannot show what the value started as.
        assertEquals(null, result.descriptors(":domain").single().default)
    }

    @Test
    fun `dependency descriptors are aggregated across a module boundary`() {
        val domain = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput val speed = 10
                """.trimIndent(),
            ),
            module = ":domain",
        ).assertSucceeded()

        val app = compile(
            temp.newFolder(),
            SourceFile(
                "App.kt",
                """
                package com.app

                import com.rohittp.debuginput.DebugInput

                @DebugInput val frameBudget = 16
                """.trimIndent(),
            ),
            module = ":app",
            dependencyDescriptors = listOf(descriptorFunctionFqName(":domain")),
            dependsOn = listOf(domain.classesDir),
        ).assertSucceeded()

        val ids = app.descriptors(":app", domain.classesDir).map { it.id }
        assertEquals(listOf("com.app.frameBudget", "com.app.physics.speed"), ids)
    }

    @Test
    fun `a diamond hands the page the shared input twice`() {
        val base = compileModule(":base", "Base.kt", "com.app.base", "poolSize", 4)
        val left = compileModule(
            ":left",
            "Left.kt",
            "com.app.left",
            "leftLimit",
            1,
            dependencies = listOf(":base"),
            dependsOn = listOf(base.classesDir),
        )
        val right = compileModule(
            ":right",
            "Right.kt",
            "com.app.right",
            "rightLimit",
            2,
            dependencies = listOf(":base"),
            dependsOn = listOf(base.classesDir),
        )
        val app = compileModule(
            ":app",
            "App.kt",
            "com.app",
            "frameBudget",
            16,
            dependencies = listOf(":left", ":right"),
            dependsOn = listOf(left.classesDir, right.classesDir, base.classesDir),
        )

        val ids = app.descriptors(":app", left.classesDir, right.classesDir, base.classesDir)
            .map { it.id }

        // Dedupe is the page's job, not the compiler's: it is the only place that sees the
        // whole aggregated list.
        assertEquals(2, ids.count { it == "com.app.base.poolSize" }, ids.toString())
        assertTrue(ids.containsAll(listOf("com.app.frameBudget", "com.app.left.leftLimit", "com.app.right.rightLimit")))
    }

    /**
     * The ADR-0006 verification item, in full: a dependent module cannot *name* the function,
     * but the plugin can resolve and call it. Both halves have to hold at once — the first is
     * what keeps the generated symbol out of consumers' completion and ABI, the second is what
     * makes hierarchical aggregation work at all.
     */
    @Test
    fun `a dependent module cannot name the descriptor function in source`() {
        val domain = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput val speed = 10
                """.trimIndent(),
            ),
            module = ":domain",
        ).assertSucceeded()

        val app = compile(
            temp.newFolder(),
            SourceFile(
                "App.kt",
                """
                package com.app

                fun probe(): Any = com.rohittp.debuginput.generated.descriptors_domain()
                """.trimIndent(),
            ),
            module = ":app",
            dependsOn = listOf(domain.classesDir),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, app.exitCode)
        assertTrue(
            app.errors.any { "Unresolved reference 'descriptors_domain'" in it.message },
            app.errors.joinToString("\n"),
        )
    }

    @Test
    fun `the descriptor function is hidden from ordinary resolution`() {
        val result = compile(
            temp.newFolder(),
            SourceFile("Empty.kt", "package com.app\n"),
            module = ":app",
        ).assertSucceeded()

        val method = result.descriptorMethod(":app")
        val annotations = File(
            result.classesDir,
            "com/rohittp/debuginput/generated/DebugInputDescriptors_appKt.class",
        ).readBytes().decodeToString(throwOnInvalidSequence = false)

        assertTrue("kotlin/Deprecated" in annotations, "no kotlin.Deprecated on the facade class")
        assertTrue("HIDDEN" in annotations, "the deprecation level is not HIDDEN")
        // HIDDEN deprecation makes the JVM method synthetic, which is what keeps it out of
        // Java autocomplete while leaving it callable.
        assertTrue(method.isSynthetic, "expected a synthetic method, got $method")
    }

    private fun compileModule(
        module: String,
        fileName: String,
        packageName: String,
        propertyName: String,
        value: Int,
        dependencies: List<String> = emptyList(),
        dependsOn: List<File> = emptyList(),
    ): CompilationResult = compile(
        temp.newFolder(),
        SourceFile(
            fileName,
            """
            package $packageName

            import com.rohittp.debuginput.DebugInput

            @DebugInput val $propertyName = $value
            """.trimIndent(),
        ),
        module = module,
        dependencyDescriptors = dependencies.map(::descriptorFunctionFqName),
        dependsOn = dependsOn,
    ).assertSucceeded()
}

internal fun CompilationResult.descriptorMethod(module: String, vararg alsoOnPath: File) =
    classLoader(*alsoOnPath)
        .loadClass("com.rohittp.debuginput.generated.DebugInputDescriptors_${sanitizeModule(module)}Kt")
        .getDeclaredMethod(descriptorFunctionName(module))
        .apply { isAccessible = true }

@Suppress("UNCHECKED_CAST")
internal fun CompilationResult.descriptors(module: String, vararg alsoOnPath: File): List<DebugInputDescriptor> =
    descriptorMethod(module, *alsoOnPath).invoke(null) as List<DebugInputDescriptor>
