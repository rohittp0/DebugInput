package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.compose.PageRenders
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `DebugInputsPage()` has to end up with this module's descriptors without the consumer ever
 * naming the generated symbol. See docs/adr/0006-linkage-by-call-site-rewriting.md.
 */
class PageCallSiteTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Before
    fun resetPage() {
        PageRenders.reset()
    }

    @Test
    fun `a defaulted call receives the enclosing module's descriptors`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
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

        result.invoke("com.app.PhysicsKt", "show")

        val rendered = PageRenders.descriptors.single()
        assertEquals(listOf("com.app.speed"), rendered.map { it.id })
    }

    @Test
    fun `an explicit descriptors argument is left alone`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app

                import com.rohittp.debuginput.DebugInput
                import com.rohittp.debuginput.compose.DebugInputsPage

                @DebugInput val speed = 10

                fun showExplicit() {
                    DebugInputsPage(descriptors = emptyList())
                }
                """.trimIndent(),
            ),
            module = ":app",
        ).assertSucceeded()

        result.invoke("com.app.PhysicsKt", "showExplicit")

        assertTrue(PageRenders.descriptors.single().isEmpty())
    }

    @Test
    fun `an Android release call site keeps its empty default`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
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
            enabled = false,
        ).assertSucceeded()

        result.invoke("com.app.PhysicsKt", "show")

        // No transform ran, so the call site was never rewritten and the page renders nothing.
        assertTrue(PageRenders.descriptors.single().isEmpty())
    }

    private fun CompilationResult.invoke(className: String, methodName: String) {
        classLoader().loadClass(className).getMethod(methodName).invoke(null)
    }
}
