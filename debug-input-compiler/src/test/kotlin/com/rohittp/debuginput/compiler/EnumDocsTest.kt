package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.compiler.fir.kdocToDocs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where a constant's `docs` comes from, and what it looks like by the time the page shows it.
 *
 * KDoc is the source rather than a re-typed annotation argument because a file like `MagicNumbers`
 * already carries the documentation — some constants have twenty lines of measurements and
 * cross-platform history — and a second copy in an annotation would drift from the first.
 *
 * Reachability was verified against 2.3.21 under **both** parsers: the CLI's LightTree and the IDE's
 * PSI produce byte-identical KDoc through the light-tree view every `KtSourceElement` exposes.
 * `parserAgnostic` is the regression test for that.
 */
class EnumDocsTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /** Copied verbatim from `MagicNumbers.MINIMUM_DURATION_CEILING_SEC`. */
    @Test
    fun `a real multi-paragraph block keeps its paragraphs and flattens its links`() {
        val docs = kdocToDocs(
            """
            /**
                 * The shortest the duration slider's ceiling may ever be, in whole seconds, so a short route
                 * still offers a minute to play with rather than collapsing to a few seconds of choice around
                 * its own floor.
                 *
                 * Read by [com.lascade.ta.shared.builder.durationRangeSecFor] together with
                 * [DURATION_CEILING_MULTIPLE]. Before that function existed the two hosts computed different
                 * ceilings from different formulas — Android `max(60, min * 2)`, iOS `51 + min + distance/5e6` —
                 * which agreed at the default and crossed over at a 51s floor, so the same route offered
                 * different choices depending on the phone.
                 */
            """.trimIndent(),
        )

        assertEquals(
            "The shortest the duration slider's ceiling may ever be, in whole seconds, so a short " +
                "route still offers a minute to play with rather than collapsing to a few seconds " +
                "of choice around its own floor." +
                "\n\n" +
                "Read by durationRangeSecFor together with DURATION_CEILING_MULTIPLE. Before that " +
                "function existed the two hosts computed different ceilings from different " +
                "formulas — Android `max(60, min * 2)`, iOS `51 + min + distance/5e6` — which " +
                "agreed at the default and crossed over at a 51s floor, so the same route offered " +
                "different choices depending on the phone.",
            docs,
        )
    }

    @Test
    fun `a single line block is unwrapped`() {
        assertEquals(
            "Vehicle texture short-side ladder for full, balanced, and economy Preview rungs.",
            kdocToDocs(
                "/** Vehicle texture short-side ladder for full, balanced, and economy Preview rungs. */",
            ),
        )
    }

    @Test
    fun `block tags and everything after them are dropped`() {
        val docs = kdocToDocs(
            """
            /**
             * The country-highlight boundary stroke widths, thinnest first.
             *
             * @param unused nothing
             * @see [com.app.Other]
             */
            """.trimIndent(),
        )

        assertEquals("The country-highlight boundary stroke widths, thinnest first.", docs)
    }

    @Test
    fun `a markdown link keeps its text and its target`() {
        assertEquals(
            "See the [guide](https://example.com) for why.",
            kdocToDocs("/** See the [guide](https://example.com) for why. */"),
        )
    }

    @Test
    fun `an entry with no doc and no annotation gets empty docs`() {
        assertEquals("", kdocToDocs("/** */"))
    }

    @Test
    fun `an explicit annotation on the constant beats its KDoc`() {
        val docs = compileAnnotated().descriptors(":magic").associate { it.displayName to it.docs }

        assertEquals("Player speed in m/s.", docs.getValue("FROM_KDOC"))
        assertEquals("explicit wins", docs.getValue("ANNOTATED"))
        assertEquals("", docs.getValue("PLAIN"))
    }

    /**
     * The asymmetry this guards against is silent: a mechanism that only worked under one parser
     * would give docs in the IDE and empty docs in the real build, or the reverse.
     *
     * Compared through the IR of the declaring file rather than through the descriptor function,
     * because the generated file itself is not written to a class file under PSI parsing in 2.3.21 —
     * a separate compiler asymmetry that does not touch what this test is about.
     */
    @Test
    fun parserAgnostic() {
        val underLightTree = compileAnnotated(lightTree = true).enumIr()
        val underPsi = compileAnnotated(lightTree = false).enumIr()

        assertEquals(underLightTree, underPsi)
        assertTrue("""docs = "Player speed in m/s."""" in underPsi, underPsi)
        assertTrue("""docs = "explicit wins"""" in underPsi, underPsi)
    }

    /** Without the `// path:` line, which carries the temporary directory this run happened in. */
    private fun CompilationResult.enumIr(): String =
        irDumps.getValue("MagicNumbers.kt")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("// path:") }
            .joinToString("\n")

    private fun compileAnnotated(lightTree: Boolean = true): CompilationResult = compile(
        temp.newFolder(),
        SourceFile(
            "MagicNumbers.kt",
            """
            package com.app.magic

            import com.rohittp.debuginput.DebugInput

            @DebugInput
            enum class MagicNumbers(vararg val values: Double) {
                /** Player speed in m/s. */
                FROM_KDOC(3.0),

                /** Ignored, because the annotation says otherwise. */
                @DebugInput(docs = "explicit wins")
                ANNOTATED(9.0),

                PLAIN(1.0);
            }
            """.trimIndent(),
        ),
        module = ":magic",
        lightTree = lightTree,
    ).assertSucceeded()
}
