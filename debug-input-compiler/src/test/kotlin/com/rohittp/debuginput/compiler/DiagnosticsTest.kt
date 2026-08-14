package com.rohittp.debuginput.compiler

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every misuse the frontend has to reject, checked through the message a build log would
 * actually show and the position it would be attached to.
 */
class DiagnosticsTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `const val is rejected`() {
        assertRejected(
            "@DebugInput const val speed = 10",
            expectedMessage = "@DebugInput cannot be applied to const val speed: a const val is " +
                "inlined at every use site, so there is no getter to intercept.",
            expectedColumn = 23,
        )
    }

    @Test
    fun `var is rejected`() {
        assertRejected(
            "@DebugInput var speed = 10",
            expectedMessage = "@DebugInput cannot be applied to var speed: the setter would write " +
                "a backing field that the rewritten getter ignores.",
            expectedColumn = 17,
        )
    }

    @Test
    fun `a custom getter is rejected`() {
        assertRejected(
            "@DebugInput val speed: Int get() = 10",
            expectedMessage = "@DebugInput cannot be applied to speed, which has a custom getter: " +
                "there is no initializer to take the default from.",
            expectedColumn = 17,
        )
    }

    @Test
    fun `a delegated property is rejected`() {
        assertRejected(
            "@DebugInput val speed: Int by lazy { 10 }",
            expectedMessage = "@DebugInput cannot be applied to the delegated property speed: " +
                "there is no initializer to take the default from.",
            expectedColumn = 17,
        )
    }

    @Test
    fun `a local val is rejected`() {
        val result = compileSnippet(
            """
            fun run(): Int {
                @DebugInput val speed = 10
                return speed
            }
            """.trimIndent(),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        assertSingleError(
            result,
            "@DebugInput cannot be applied to the local val speed: a local val has no fully " +
                "qualified name to derive an id from.",
        )
    }

    @Test
    fun `an unsupported type is rejected and names the type`() {
        val result = compileSnippet("@DebugInput val timeout: java.time.Duration? = null")

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        val message = assertSingleError(result).message
        assertTrue("of type java.time.Duration?" in message, message)
        assertTrue("nothing knows how to store that type" in message, message)
    }

    @Test
    fun `a nested container is rejected and points at custom renderers`() {
        val result = compileSnippet("@DebugInput val grid: List<List<Int>> = emptyList()")

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        val message = assertSingleError(result).message
        assertTrue(
            "of type kotlin.collections.List<kotlin.collections.List<kotlin.Int>>" in message,
            message,
        )
        assertTrue("a custom renderer is the escape hatch" in message, message)
    }

    @Test
    fun `a container nested inside a tuple is rejected`() {
        val result = compileSnippet(
            """@DebugInput val attempt: Pair<Int, List<String>> = 1 to emptyList()""",
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        val message = assertSingleError(result).message
        assertTrue(
            "of type kotlin.Pair<kotlin.Int, kotlin.collections.List<kotlin.String>>" in message,
            message,
        )
        assertTrue("a container may hold scalars and nothing else" in message, message)
    }

    @Test
    fun `a Map is rejected whatever its key and value types are`() {
        for (declaration in listOf(
            "@DebugInput val limits: Map<String, Int> = emptyMap()",
            "@DebugInput val limits: HashMap<String, Int> = HashMap()",
            "@DebugInput val limits: Map<String, List<Int>> = emptyMap()",
        )) {
            val result = compileSnippet(declaration)

            assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, declaration)
            val message = assertSingleError(result).message
            assertTrue("Map is not one of the supported container types" in message, message)
        }
    }

    /**
     * The one diagnostic that looks arbitrary from the outside, so its message has to carry the
     * reason rather than just the rule.
     */
    @Test
    fun `an enum inside a container is rejected and says why`() {
        val result = compileSnippet(
            """
            enum class Tier { FREE, PRO }

            @DebugInput val tiers: List<Tier> = emptyList()
            """.trimIndent(),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        val message = assertSingleError(result).message
        assertTrue("of type kotlin.collections.List<com.app.physics.Tier>" in message, message)
        assertTrue("supported as an input on its own but not inside a container" in message, message)
        assertTrue("needs the table of constants for that enum" in message, message)
        assertTrue("Kotlin/Native has no reflection to recover it" in message, message)
    }

    @Test
    fun `an enum on its own is accepted`() {
        val result = compileSnippet(
            """
            enum class Tier { FREE, PRO }

            @DebugInput val tier: Tier = Tier.FREE
            """.trimIndent(),
        )

        assertEquals(ExitCode.OK, result.exitCode, result.errors.joinToString("\n"))
    }

    @Test
    fun `a nullable scalar is rejected`() {
        val result = compileSnippet("@DebugInput val speed: Int? = 10")

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("of type kotlin.Int?" in assertSingleError(result).message)
    }

    @Test
    fun `a mutable collection is rejected rather than silently rebuilt as read-only`() {
        val result = compileSnippet(
            "@DebugInput val hosts: MutableList<String> = mutableListOf()",
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            "of type kotlin.collections.MutableList<kotlin.String>" in assertSingleError(result).message,
        )
    }

    @Test
    fun `an expect property is rejected`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput expect val speed: Int
                """.trimIndent(),
            ),
            multiPlatform = true,
        )

        val messages = result.errors.map { it.message }
        assertTrue(
            messages.any { "which is an expect or actual property" in it && "speed" in it },
            "expected an expect/actual rejection, got:\n${result.errors.joinToString("\n")}",
        )
    }

    @Test
    fun `a well formed input produces no diagnostic`() {
        val result = compileSnippet("@DebugInput val speed = 10")

        assertEquals(ExitCode.OK, result.exitCode)
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
    }

    @Test
    fun `diagnostics still run when the transform is disabled`() {
        val result = compileSnippet("@DebugInput const val speed = 10", enabled = false)

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode)
        assertSingleError(
            result,
            "@DebugInput cannot be applied to const val speed: a const val is inlined at every " +
                "use site, so there is no getter to intercept.",
        )
    }

    private fun assertRejected(declaration: String, expectedMessage: String, expectedColumn: Int) {
        val result = compileSnippet(declaration)

        assertEquals(
            ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "expected a compilation error, got ${result.diagnostics}",
        )
        val error = assertSingleError(result, expectedMessage)
        // The snippet puts the declaration on line 5, right after the package and import.
        assertEquals(5, error.line, "wrong line for $error")
        assertEquals(expectedColumn, error.column, "wrong column for $error")
    }

    private fun assertSingleError(result: CompilationResult, expectedMessage: String): Diagnostic {
        val error = assertSingleError(result)
        assertEquals(expectedMessage, error.message)
        return error
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
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                $declaration
                """.trimIndent(),
            ),
            enabled = enabled,
        )
}
