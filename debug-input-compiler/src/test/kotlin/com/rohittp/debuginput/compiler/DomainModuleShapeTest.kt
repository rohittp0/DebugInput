package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.DebugInputDescriptor
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A copy of the `:domain` dogfood module's shape, because every earlier test was a simpler shape
 * and Task 6 shipped broken twice over on this one.
 *
 * What it reproduces that nothing else did:
 * - **a multiplatform source-set hierarchy**, so the frontend runs five sessions rather than one.
 *   Generating a top-level declaration in each of them gave five identical `descriptors_domain`
 *   functions, which Native rejects as a signature clash and JVM turns into a body-less function;
 * - **two files in one package**, so a per-file helper has to be uniquely named and the module
 *   function has to aggregate more than one of them;
 * - **an `object` member and a private top-level input**, whose backing fields are only readable
 *   from their own file — verified by `verifyIrVisibility`, which the harness now always enables;
 * - **a non-constant default** (`baseLimit * 5`), which cannot be recovered from the initializer
 *   without re-evaluating it, so the descriptor must genuinely read the field;
 * - **`manifestOut` set**, alongside everything else;
 * - **a composite, a primitive array and an enum**, so the spec literals the page dispatches on
 *   are pinned by a test rather than by inspection.
 *
 * Keep it in step with `domain/src/commonMain/kotlin/com/rohittp/debuginput/sample/domain/`.
 */
class DomainModuleShapeTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `the descriptor function has a body and returns every input`() {
        val manifest = File(temp.newFolder(), "descriptors.json")
        val result = compileDomainShape(manifest)

        val descriptors = result.descriptors(":domain")

        // A set, because the order files reach the backend in is the compiler's business, not
        // something the plugin chooses. Order within a file is asserted below.
        assertEquals(
            setOf(
                "com.rohittp.debuginput.sample.domain.speed",
                "com.rohittp.debuginput.sample.domain.Physics.kt.droppedFrameBudget",
                "com.rohittp.debuginput.sample.domain.Tiers.freeLimit",
                "com.rohittp.debuginput.sample.domain.Tiers.paidLimit",
                "com.rohittp.debuginput.sample.domain.hosts",
                "com.rohittp.debuginput.sample.domain.retryDelays",
                "com.rohittp.debuginput.sample.domain.tier",
            ),
            descriptors.mapTo(mutableSetOf()) { it.id },
        )

        assertEquals(
            mapOf(
                "speed" to "Physics",
                "droppedFrameBudget" to "Physics",
                "freeLimit" to "Tiers",
                "paidLimit" to "Tiers",
                "hosts" to "Networking",
                "retryDelays" to "Networking",
                "tier" to "Networking",
            ),
            descriptors.associate { it.displayName to it.section },
        )
        assertEquals(listOf(":domain"), descriptors.map { it.module }.distinct())
        assertEquals(
            "Frames dropped before the animation degrades",
            descriptors.single { it.id.endsWith("droppedFrameBudget") }.docs,
        )

        // enumConstants is the one parameter the plugin still says nothing about, so the
        // constructor's own default has to fill it in across a module boundary.
        assertEquals(listOf(null), descriptors.map { it.enumConstants }.distinct())
    }

    /**
     * The spec literal is what the page dispatches renderers on, so a wrong one is a wrong editor
     * rather than a compile failure. `parseTypeSpec` in the runtime's `Codec.kt` is the authority
     * on what these have to look like.
     */
    @Test
    fun `each input carries the spec literal and type key for its own type`() {
        val descriptors = compileDomainShape(File(temp.newFolder(), "descriptors.json"))
            .descriptors(":domain")
            .associateBy { it.displayName }

        assertEquals("int" to "kotlin.Int", descriptors.getValue("speed").let { it.spec to it.typeKey })
        assertEquals(
            "lst<str>" to "kotlin.collections.List",
            descriptors.getValue("hosts").let { it.spec to it.typeKey },
        )
        assertEquals(
            "iarr" to "kotlin.IntArray",
            descriptors.getValue("retryDelays").let { it.spec to it.typeKey },
        )
        assertEquals(
            "enm" to "com.rohittp.debuginput.sample.domain.Tier",
            descriptors.getValue("tier").let { it.spec to it.typeKey },
        )
    }

    @Test
    fun `defaults are read from the backing field, including the composite ones`() {
        val descriptors = compileDomainShape(File(temp.newFolder(), "descriptors.json"))
            .descriptors(":domain")
            .associateBy { it.displayName }

        assertEquals(10, descriptors.getValue("speed").default)
        // Computed from another property, so it cannot be recovered from the initializer without
        // evaluating it twice.
        assertEquals(25, descriptors.getValue("freeLimit").default)
        assertEquals(listOf("api.example.com", "cdn.example.com"), descriptors.getValue("hosts").default)
        assertContentEquals(intArrayOf(100, 400), descriptors.getValue("retryDelays").default as IntArray)
        assertEquals("FREE", (descriptors.getValue("tier").default as Enum<*>).name)
    }

    @Test
    fun `exactly one descriptor function is generated across the source set hierarchy`() {
        val result = compileDomainShape(File(temp.newFolder(), "descriptors.json"))

        // Five frontend sessions, one declaration. Anything else is a signature clash on Native.
        // The JVM backend emits only one facade class either way, so count the files the module
        // fragment actually carries rather than the ones that reached disk.
        assertEquals(
            1,
            result.irFileNames.count { it == "DebugInputDescriptors_domain.kt" },
            result.irFileNames.toString(),
        )
        assertEquals(
            listOf("DebugInputDescriptors_domainKt.class"),
            File(result.classesDir, "com/rohittp/debuginput/generated").listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun `the manifest names the module, its function, and every input with its type key`() {
        val manifest = File(temp.newFolder(), "descriptors.json")
        compileDomainShape(manifest)
        val json = manifest.readText()

        assertTrue(json.startsWith("""{"module":":domain","function":"$DESCRIPTOR_FUNCTION","inputs":["""), json)
        assertTrue(json.endsWith("]}"), json)

        val prefix = "com.rohittp.debuginput.sample.domain"
        for (entry in listOf(
            """{"id":"$prefix.speed","typeKey":"kotlin.Int"}""",
            """{"id":"$prefix.Physics.kt.droppedFrameBudget","typeKey":"kotlin.Int"}""",
            """{"id":"$prefix.Tiers.freeLimit","typeKey":"kotlin.Int"}""",
            """{"id":"$prefix.Tiers.paidLimit","typeKey":"kotlin.Int"}""",
            """{"id":"$prefix.hosts","typeKey":"kotlin.collections.List"}""",
            """{"id":"$prefix.retryDelays","typeKey":"kotlin.IntArray"}""",
            """{"id":"$prefix.tier","typeKey":"$prefix.Tier"}""",
        )) {
            assertTrue(entry in json, "missing $entry in $json")
        }

        // Declaration order within a file is the plugin's to keep, and the page sorts anyway.
        assertTrue(json.indexOf("$prefix.speed") < json.indexOf("droppedFrameBudget"), json)
        assertTrue(json.indexOf("freeLimit") < json.indexOf("paidLimit"), json)
        assertTrue(json.indexOf("$prefix.hosts") < json.indexOf("retryDelays"), json)
        assertTrue(json.indexOf("retryDelays") < json.indexOf("$prefix.tier"), json)
    }

    private fun compileDomainShape(manifest: File): CompilationResult = compile(
        temp.newFolder(),
        SourceFile(
            "Physics.kt",
            """
            package com.rohittp.debuginput.sample.domain

            import com.rohittp.debuginput.DebugInput

            @DebugInput(docs = "Player speed in m/s")
            val speed: Int = 10

            @DebugInput(docs = "Frames dropped before the animation degrades")
            private val droppedFrameBudget: Int = 3

            fun animationBudget(): Int = droppedFrameBudget
            """.trimIndent(),
        ),
        SourceFile(
            "Tiers.kt",
            """
            package com.rohittp.debuginput.sample.domain

            import com.rohittp.debuginput.DebugInput

            object Tiers {

                @DebugInput(docs = "Items a free account may create")
                val freeLimit: Int = baseLimit * 5

                @DebugInput(docs = "Items a paid account may create")
                val paidLimit: Int = 1_000
            }

            private val baseLimit: Int = 5
            """.trimIndent(),
        ),
        SourceFile(
            "Networking.kt",
            """
            package com.rohittp.debuginput.sample.domain

            import com.rohittp.debuginput.DebugInput

            enum class Tier { FREE, PRO, TEAM }

            @DebugInput(docs = "Hosts the client will try, in order")
            val hosts: List<String> = listOf("api.example.com", "cdn.example.com")

            @DebugInput(docs = "Backoff between retries, in milliseconds")
            val retryDelays: IntArray = intArrayOf(100, 400)

            @DebugInput(docs = "Account tier the client pretends to be on")
            val tier: Tier = Tier.FREE
            """.trimIndent(),
        ),
        module = ":domain",
        manifestOut = manifest,
        sourceSetHierarchy = listOf(
            "commonMain",
            "nativeMain",
            "appleMain",
            "iosMain",
            "iosSimulatorArm64Main",
        ),
    ).assertSucceeded()

    private companion object {
        const val DESCRIPTOR_FUNCTION = "com.rohittp.debuginput.generated.descriptors_domain"
    }
}
