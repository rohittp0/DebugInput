package com.rohittp.debuginput.compiler

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `@DebugInput` on a class: what it means on an enum, and what it is rejected for everywhere else. */
class EnumClassDiagnosticsTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /**
     * The annotation has `CLASS` in its `@Target`, so before this diagnostic existed putting it on
     * an ordinary class compiled to nothing at all. A silent no-op is worse than a rejection.
     */
    @Test
    fun `a non-enum class is rejected`() {
        for (declaration in listOf(
            "@DebugInput class Physics(val speed: Int)",
            "@DebugInput object Config",
            "@DebugInput interface Marker",
            "@DebugInput data class Point(val x: Int, val y: Int)",
        )) {
            val result = compileSnippet(declaration)

            assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, declaration)
            val message = assertSingleError(result).message
            assertTrue("only means something for an enum class" in message, message)
        }
    }

    @Test
    fun `an enum with no supported constructor val is rejected`() {
        val result = compileSnippet(
            """
            @DebugInput
            enum class Mode { FAST, SLOW }
            """.trimIndent(),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        val message = assertSingleError(result).message
        assertTrue("has nothing to instrument" in message, message)
        assertTrue("shared by every constant" in message, message)
    }

    /** A `val` declared in the enum body is one value for the whole enum, not one per constant. */
    @Test
    fun `a body val does not count as a constructor val`() {
        val result = compileSnippet(
            """
            @DebugInput
            enum class Mode {
                FAST, SLOW;

                val limit: Int = 10
            }
            """.trimIndent(),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("has nothing to instrument" in assertSingleError(result).message)
    }

    @Test
    fun `a constructor var is rejected on its own`() {
        val result = compileSnippet(
            """
            @DebugInput
            enum class Mode(var limit: Int, val label: String) {
                FAST(1, "fast"), SLOW(2, "slow"),
            }
            """.trimIndent(),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        val message = assertSingleError(result).message
        assertTrue("cannot instrument Mode.limit: it is a var" in message, message)
        assertTrue("Every other constructor val of Mode is still instrumented" in message, message)
    }

    @Test
    fun `an unsupported constructor val type is rejected without failing the whole enum`() {
        val result = compileSnippet(
            """
            @DebugInput
            enum class Mode(val limit: Int, val grid: List<List<Int>>) {
                FAST(1, emptyList()), SLOW(2, emptyList()),
            }
            """.trimIndent(),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        val message = assertSingleError(result).message
        assertTrue("cannot instrument Mode.grid" in message, message)
        assertTrue(
            "of type kotlin.collections.List<kotlin.collections.List<kotlin.Int>>" in message,
            message,
        )
        assertTrue("Every other constructor val of the enum is still instrumented" in message, message)
    }

    /**
     * One bad `val` among several is reported on its own. The compilation fails either way, so what
     * is testable here is that the message points at the one offending val and the enum-level
     * "nothing to instrument" error does not pile on top of it.
     */
    @Test
    fun `one unsupported constructor val produces one error and no blanket rejection`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Modes.kt",
                """
                package com.app.modes

                import com.rohittp.debuginput.DebugInput

                @DebugInput
                enum class Mode(val limit: Int, val grid: List<List<Int>>) {
                    FAST(1, emptyList()),
                    SLOW(2, emptyList()),
                }
                """.trimIndent(),
            ),
            module = ":modes",
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        val ours = result.errors.filter { "@DebugInput" in it.message }
        assertEquals(1, ours.size, ours.joinToString("\n"))
        assertTrue("cannot instrument Mode.grid" in ours.single().message)
    }

    @Test
    fun `class diagnostics still run when the transform is disabled`() {
        val result = compileSnippet("@DebugInput object Config", enabled = false)

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("only means something for an enum class" in assertSingleError(result).message)
    }

    private fun assertSingleError(result: CompilationResult): Diagnostic {
        val ours = result.errors.filter { "@DebugInput" in it.message }
        assertEquals(
            1,
            ours.size,
            "expected exactly one debug-input error, got:\n${result.errors.joinToString("\n")}",
        )
        return ours.single()
    }

    private fun compileSnippet(declaration: String, enabled: Boolean = true): CompilationResult =
        compile(
            temp.newFolder(),
            SourceFile(
                "Modes.kt",
                """
                package com.app.modes

                import com.rohittp.debuginput.DebugInput

                $declaration
                """.trimIndent(),
            ),
            module = ":modes",
            enabled = enabled,
        )
}
