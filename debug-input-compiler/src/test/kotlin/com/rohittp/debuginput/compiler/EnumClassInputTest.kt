package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.DebugInputDescriptor
import com.rohittp.debuginput.DebugInputRegistry
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@DebugInput` on an enum class, in the shape of the file that drove the feature —
 * `MagicNumbers(vararg val values: Double)` with a hundred constants, some carrying several values.
 *
 * The risk this suite exists for is the shared getter: one `values` getter serves every constant, so
 * it has to pick its id off the receiver. Get that wrong and every constant reads the same override.
 */
class EnumClassInputTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Before
    fun resetRegistry() {
        DebugInputRegistry.reset()
    }

    @Test
    fun `every constant becomes an input of its own`() {
        val descriptors = compileEnums().descriptors(":magic").filter { it.section == "MagicNumbers" }

        assertEquals(
            listOf(
                "com.app.magic.MagicNumbers.INTRO_INFLEXION.values",
                "com.app.magic.MagicNumbers.ROUTE_LINE_WIDTHS.values",
                "com.app.magic.MagicNumbers.FLAG_SCALES.values",
                "com.app.magic.MagicNumbers.WITH_BODY.values",
                "com.app.magic.MagicNumbers.LAST.values",
            ),
            descriptors.map { it.id },
        )
        assertEquals(listOf("darr"), descriptors.map { it.spec }.distinct())
        assertEquals(listOf("kotlin.DoubleArray"), descriptors.map { it.typeKey }.distinct())
    }

    /**
     * One supported constructor `val`, so the property name is dropped: `.values` repeated down a
     * hundred rows is noise the section header already covers.
     */
    @Test
    fun `a single constructor val is displayed as the constant alone`() {
        val descriptors = compileEnums().descriptors(":magic").filter { it.section == "MagicNumbers" }

        assertEquals(
            listOf("INTRO_INFLEXION", "ROUTE_LINE_WIDTHS", "FLAG_SCALES", "WITH_BODY", "LAST"),
            descriptors.map { it.displayName },
        )
    }

    /** Two of them, so the property name is what tells two rows of the same constant apart. */
    @Test
    fun `several constructor vals are displayed as constant and property`() {
        val descriptors = compileEnums().descriptors(":magic").filter { it.section == "Tier" }

        assertEquals(
            setOf("FREE.limit", "FREE.label", "PRO.limit", "PRO.label"),
            descriptors.mapTo(mutableSetOf()) { it.displayName },
        )
        assertEquals(
            setOf(
                "com.app.magic.Tier.FREE.limit",
                "com.app.magic.Tier.FREE.label",
                "com.app.magic.Tier.PRO.limit",
                "com.app.magic.Tier.PRO.label",
            ),
            descriptors.mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(setOf("int", "str"), descriptors.mapTo(mutableSetOf()) { it.spec })
    }

    @Test
    fun `each constant's default is read from its own instance`() {
        val descriptors = compileEnums().descriptors(":magic").associateBy { it.displayName }

        assertContentEquals(doubleArrayOf(3.0), descriptors.getValue("INTRO_INFLEXION").default as DoubleArray)
        assertContentEquals(
            doubleArrayOf(2.0, 4.0, 6.0),
            descriptors.getValue("ROUTE_LINE_WIDTHS").default as DoubleArray,
        )
        assertContentEquals(doubleArrayOf(0.6, 0.8, 1.0), descriptors.getValue("FLAG_SCALES").default as DoubleArray)
        assertContentEquals(doubleArrayOf(7.0), descriptors.getValue("WITH_BODY").default as DoubleArray)

        assertEquals(10, descriptors.getValue("FREE.limit").default)
        assertEquals("Free", descriptors.getValue("FREE.label").default)
        assertEquals(1_000, descriptors.getValue("PRO.limit").default)
        assertEquals("Pro", descriptors.getValue("PRO.label").default)
    }

    /**
     * The whole point of the ordinal-indexed id table. A getter shared by every constant that got
     * its id wrong would hand all of them the same override, and no other test would notice.
     */
    @Test
    fun `an override on one constant leaves its siblings alone`() {
        val result = compileEnums()
        DebugInputRegistry.overrides["com.app.magic.MagicNumbers.FLAG_SCALES.values"] =
            doubleArrayOf(9.0)

        assertContentEquals(doubleArrayOf(3.0), result.readValues("INTRO_INFLEXION"))
        assertContentEquals(doubleArrayOf(2.0, 4.0, 6.0), result.readValues("ROUTE_LINE_WIDTHS"))
        assertContentEquals(doubleArrayOf(9.0), result.readValues("FLAG_SCALES"))
        assertContentEquals(doubleArrayOf(7.0), result.readValues("WITH_BODY"))
        assertContentEquals(doubleArrayOf(9.0), result.readValues("LAST"))

        assertEquals(
            listOf(
                "com.app.magic.MagicNumbers.INTRO_INFLEXION.values",
                "com.app.magic.MagicNumbers.ROUTE_LINE_WIDTHS.values",
                "com.app.magic.MagicNumbers.FLAG_SCALES.values",
                "com.app.magic.MagicNumbers.WITH_BODY.values",
                "com.app.magic.MagicNumbers.LAST.values",
            ),
            DebugInputRegistry.calls.map { it.id },
        )
        assertEquals(listOf("resolveComposite"), DebugInputRegistry.calls.map { it.resolver }.distinct())
    }

    /**
     * An entry with a body is an anonymous subclass, so the getter runs on a class the id table was
     * not built for. `ordinal` is inherited and still correct, which is what keeps the table valid.
     */
    @Test
    fun `an entry with a body indexes the table by its own ordinal`() {
        val result = compileEnums()
        DebugInputRegistry.overrides["com.app.magic.MagicNumbers.WITH_BODY.values"] =
            doubleArrayOf(1.0, 2.0)

        assertContentEquals(doubleArrayOf(1.0, 2.0), result.readValues("WITH_BODY"))
        assertContentEquals(doubleArrayOf(9.0), result.readValues("LAST"))
        assertEquals("body", result.invoke("com.app.magic.MagicNumbersKt", "describeWithBody"))
    }

    @Test
    fun `scalar constructor vals take the unboxed fast paths`() {
        val result = compileEnums()
        DebugInputRegistry.overrides["com.app.magic.Tier.PRO.limit"] = 5_000

        assertEquals(10, result.invoke("com.app.magic.MagicNumbersKt", "readFreeLimit"))
        assertEquals(5_000, result.invoke("com.app.magic.MagicNumbersKt", "readProLimit"))
        assertEquals("Free", result.invoke("com.app.magic.MagicNumbersKt", "readFreeLabel"))

        assertEquals(
            listOf("resolveInt", "resolveInt", "resolveString"),
            DebugInputRegistry.calls.map { it.resolver },
        )
        assertEquals(listOf(""), DebugInputRegistry.calls.map { it.spec }.distinct())
    }

    @Test
    fun `an override survives repeated reads without rebuilding the id`() {
        val result = compileEnums()

        repeat(3) { assertContentEquals(doubleArrayOf(3.0), result.readValues("INTRO_INFLEXION")) }

        assertEquals(
            listOf("com.app.magic.MagicNumbers.INTRO_INFLEXION.values"),
            DebugInputRegistry.calls.map { it.id }.distinct(),
        )
    }

    @Test
    fun `an Android release compilation leaves the enum alone`() {
        val result = compileEnums(enabled = false)

        assertContentEquals(doubleArrayOf(3.0), result.readValues("INTRO_INFLEXION"))
        assertTrue(DebugInputRegistry.calls.isEmpty())
        assertTrue(
            File(result.classesDir, "com/rohittp/debuginput").listFiles().isNullOrEmpty(),
            "generated output leaked into a release compilation",
        )
    }

    private fun CompilationResult.readValues(constant: String): DoubleArray =
        classLoader().loadClass("com.app.magic.MagicNumbersKt")
            .getMethod("readValues", String::class.java)
            .invoke(null, constant) as DoubleArray

    private fun CompilationResult.invoke(className: String, method: String): Any? =
        classLoader().loadClass(className).getMethod(method).invoke(null)

    private fun compileEnums(enabled: Boolean = true): CompilationResult = compile(
        temp.newFolder(),
        SourceFile(
            "MagicNumbers.kt",
            """
            package com.app.magic

            import com.rohittp.debuginput.DebugInput

            @DebugInput
            enum class MagicNumbers(vararg val values: Double) {
                INTRO_INFLEXION(3.0),
                ROUTE_LINE_WIDTHS(2.0, 4.0, 6.0),
                FLAG_SCALES(0.6, 0.8, 1.0),
                WITH_BODY(7.0) {
                    override fun toString(): String = "body"
                },
                LAST(9.0);

                fun doubles(): List<Double> = values.toList()
            }

            @DebugInput
            enum class Tier(val limit: Int, val label: String) {
                FREE(10, "Free"),
                PRO(1_000, "Pro"),
            }

            fun readValues(name: String): DoubleArray = MagicNumbers.valueOf(name).values

            fun describeWithBody(): String = MagicNumbers.WITH_BODY.toString()

            fun readFreeLimit(): Int = Tier.FREE.limit

            fun readProLimit(): Int = Tier.PRO.limit

            fun readFreeLabel(): String = Tier.FREE.label
            """.trimIndent(),
        ),
        module = ":magic",
        enabled = enabled,
    ).assertSucceeded()
}
