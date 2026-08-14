package com.rohittp.debuginput.compiler

import java.io.File
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A deliberately small set of IR snapshots. The behaviour tests are the ones that say
 * whether the plugin works; these say what it emitted, so an unintended change to the
 * shape of the generated getter shows up as a diff rather than as a passing test.
 *
 * Run with `UPDATE_GOLDEN=true` to rewrite the expected files, then read the diff.
 */
class GoldenIrTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun scalarInt() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput(docs = "Player speed in m/s")
                val speed = 10
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertGolden("scalarInt", result.irDumps.getValue("Physics.kt"))
    }

    @Test
    fun privateTopLevel() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput private val speed = 10

                fun readSpeed(): Int = speed
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertGolden("privateTopLevel", result.irDumps.getValue("Physics.kt"))
    }

    @Test
    fun androidReleaseNoop() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput val speed = 10

                fun readSpeed(): Int = speed
                """.trimIndent(),
            ),
            enabled = false,
        ).assertSucceeded()

        assertGolden("androidReleaseNoop", result.irDumps.getValue("Physics.kt"))
    }

    /** The one getter shape M2 adds: a generic call with the result cast back. */
    @Test
    fun compositeGetter() {
        val result = compile(
            temp.newFolder(),
            // The enum lives in its own file: its IR dump is pages of JVM fake overrides that a
            // JDK upgrade would rewrite, and none of that is this plugin's output.
            SourceFile("Tier.kt", "package com.app.net\n\nenum class Tier { FREE, PRO }\n"),
            SourceFile(
                "Networking.kt",
                """
                package com.app.net

                import com.rohittp.debuginput.DebugInput

                @DebugInput val hosts: List<String> = listOf("api.example.com")

                @DebugInput val retryDelays: IntArray = intArrayOf(100)

                @DebugInput val tier: Tier = Tier.FREE
                """.trimIndent(),
            ),
            module = ":net",
        ).assertSucceeded()

        assertGolden("compositeGetter", result.irDumps.getValue("Networking.kt"))
    }

    /**
     * The shape M3 adds: one getter for every constant, its id taken off the receiver through an
     * ordinal-indexed table, and a per-constant default read from the constant instance.
     */
    @Test
    fun enumClassInput() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "MagicNumbers.kt",
                """
                package com.app.magic

                import com.rohittp.debuginput.DebugInput

                @DebugInput
                enum class MagicNumbers(vararg val values: Double) {
                    /** Player speed in m/s. */
                    INTRO_INFLEXION(3.0),
                    ROUTE_LINE_WIDTHS(2.0, 4.0, 6.0);
                }
                """.trimIndent(),
            ),
            module = ":magic",
        ).assertSucceeded()

        assertGolden("enumClassInput", result.irDumps.getValue("MagicNumbers.kt"))
    }

    @Test
    fun descriptorFunction() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput(docs = "Player speed in m/s")
                val speed = 10
                """.trimIndent(),
            ),
            module = ":domain",
            dependencyDescriptors = listOf(descriptorFunctionFqName(":shared")),
        ).assertSucceeded()

        // The :shared dependency is deliberately unresolvable here, so the snapshot shows the
        // shape of a module whose own inputs are all there is. Cross-module aggregation is
        // covered by DescriptorFunctionTest, which asserts behaviour rather than shape.
        assertGolden("descriptorFunction", result.irDumps.getValue("DebugInputDescriptors_domain.kt"))
    }

    @Test
    fun rewrittenPageCall() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Screen.kt",
                """
                package com.app

                import com.rohittp.debuginput.DebugInput
                import com.rohittp.debuginput.compose.DebugInputsPage

                @DebugInput val speed = 10

                fun show() {
                    DebugInputsPage()
                }
                """.trimIndent(),
            ),
            module = ":app",
        ).assertSucceeded()

        assertGolden("rewrittenPageCall", result.irDumps.getValue("Screen.kt"))
    }

    private fun assertGolden(name: String, dump: String) {
        val goldenDir = requireNotNull(System.getProperty("debugInput.goldenDir")) {
            "debugInput.goldenDir is set by the test task"
        }
        val expectedFile = File(goldenDir, "$name.ir.txt")
        val actual = normalize(dump)

        if (!expectedFile.exists() || System.getenv("UPDATE_GOLDEN") == "true") {
            expectedFile.parentFile.mkdirs()
            expectedFile.writeText(actual)
            error("Wrote ${expectedFile.path}. Review the diff and run the test again.")
        }

        assertEquals(expectedFile.readText(), actual, "IR for $name changed")
    }

    /**
     * Drops the absolute path of the temporary source file, and the inherited members the dump
     * prints for an enum: `clone`, `describeConstable` and `finalize` are the JDK\'s, not this
     * plugin\'s output, and a JDK upgrade rewriting them would fail a snapshot about something else.
     */
    private fun normalize(dump: String): String =
        dump.lineSequence()
            .filterNot { it.trimStart().startsWith("// path:") }
            .filterNot { "/* fake */" in it }
            .filterNot { it.trim() in INHERITED_MEMBER_ANNOTATIONS }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trimEnd() + "\n"

    private companion object {
        val INHERITED_MEMBER_ANNOTATIONS = setOf(
            "@IntrinsicConstEvaluation",
            """@Deprecated(message = "Deprecated in Java")""",
        )
    }
}
