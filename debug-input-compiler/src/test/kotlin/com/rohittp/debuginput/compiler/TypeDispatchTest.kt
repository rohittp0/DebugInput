package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.DebugInputRegistry
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Type dispatch, pinned twice over: which registry entry point each type's getter calls, and what
 * codec spec literal reaches the descriptor. The page picks its renderer off the spec, so a wrong
 * spec is a wrong editor rather than a compile failure — nothing else would catch it.
 *
 * The tags are ADR-0008's; `parseTypeSpec` in the runtime's `Codec.kt` is the authority on what
 * parses.
 */
class TypeDispatchTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Before
    fun resetRegistry() {
        DebugInputRegistry.reset()
    }

    @Test
    fun `every supported type calls its own resolver and carries its own spec`() {
        val result = compileEveryType()

        result.readAll()

        assertEquals(
            listOf(
                "int" to "resolveInt",
                "lng" to "resolveLong",
                "sht" to "resolveShort",
                "byt" to "resolveByte",
                "flt" to "resolveFloat",
                "dbl" to "resolveDouble",
                "bln" to "resolveBoolean",
                "chr" to "resolveChar",
                "str" to "resolveString",
                "enm" to "resolveEnum",
                "lst<str>" to "resolveComposite",
                "set<int>" to "resolveComposite",
                "arr<str>" to "resolveComposite",
                "iarr" to "resolveComposite",
                "larr" to "resolveComposite",
                "sarr" to "resolveComposite",
                "barr" to "resolveComposite",
                "farr" to "resolveComposite",
                "darr" to "resolveComposite",
                "zarr" to "resolveComposite",
                "carr" to "resolveComposite",
                "pair<int,str>" to "resolveComposite",
                "trip<int,int,bln>" to "resolveComposite",
            ),
            specsByProperty(result).values.zip(DebugInputRegistry.calls.map { it.resolver }),
        )

        // A scalar fast path is unboxed and never parses a spec, so it is passed none.
        val scalarCalls = DebugInputRegistry.calls.filter { it.resolver != "resolveComposite" }
        assertEquals(listOf(""), scalarCalls.map { it.spec }.distinct())
    }

    @Test
    fun `the descriptor spec and type key match the declared type`() {
        val result = compileEveryType()

        val descriptors = result.descriptors(":types").associateBy { it.displayName }

        assertEquals("int" to "kotlin.Int", descriptors.specAndKey("scalarInt"))
        assertEquals("lng" to "kotlin.Long", descriptors.specAndKey("scalarLong"))
        assertEquals("sht" to "kotlin.Short", descriptors.specAndKey("scalarShort"))
        assertEquals("byt" to "kotlin.Byte", descriptors.specAndKey("scalarByte"))
        assertEquals("flt" to "kotlin.Float", descriptors.specAndKey("scalarFloat"))
        assertEquals("dbl" to "kotlin.Double", descriptors.specAndKey("scalarDouble"))
        assertEquals("bln" to "kotlin.Boolean", descriptors.specAndKey("scalarBoolean"))
        assertEquals("chr" to "kotlin.Char", descriptors.specAndKey("scalarChar"))
        assertEquals("str" to "kotlin.String", descriptors.specAndKey("scalarString"))
        assertEquals("enm" to "com.app.types.Tier", descriptors.specAndKey("tier"))
        assertEquals("lst<str>" to "kotlin.collections.List", descriptors.specAndKey("hosts"))
        assertEquals("set<int>" to "kotlin.collections.Set", descriptors.specAndKey("ports"))
        assertEquals("arr<str>" to "kotlin.Array", descriptors.specAndKey("names"))
        assertEquals("iarr" to "kotlin.IntArray", descriptors.specAndKey("ints"))
        assertEquals("larr" to "kotlin.LongArray", descriptors.specAndKey("longs"))
        assertEquals("sarr" to "kotlin.ShortArray", descriptors.specAndKey("shorts"))
        assertEquals("barr" to "kotlin.ByteArray", descriptors.specAndKey("bytes"))
        assertEquals("farr" to "kotlin.FloatArray", descriptors.specAndKey("floats"))
        assertEquals("darr" to "kotlin.DoubleArray", descriptors.specAndKey("doubles"))
        assertEquals("zarr" to "kotlin.BooleanArray", descriptors.specAndKey("booleans"))
        assertEquals("carr" to "kotlin.CharArray", descriptors.specAndKey("chars"))
        assertEquals("pair<int,str>" to "kotlin.Pair", descriptors.specAndKey("attempt"))
        assertEquals("trip<int,int,bln>" to "kotlin.Triple", descriptors.specAndKey("window"))
    }

    @Test
    fun `an enum getter is handed its own table of constants`() {
        val result = compileEveryType()
        result.readAll()

        val call = DebugInputRegistry.calls.single { it.resolver == "resolveEnum" }
        // Kotlin/Native cannot recover this by reflection, so the call site passes it.
        assertEquals(listOf("FREE", "PRO", "TEAM"), call.entries)
        assertEquals("com.app.types.tier", call.id)
    }

    @Test
    fun `an override reaches a scalar getter`() {
        val result = compileEveryType()
        DebugInputRegistry.overrides["com.app.types.scalarString"] = "overridden"

        assertEquals("overridden", result.readAll()[8])
    }

    @Test
    fun `an override reaches a composite getter through the cast`() {
        val result = compileEveryType()
        DebugInputRegistry.overrides["com.app.types.hosts"] = listOf("cdn.example.com")
        DebugInputRegistry.overrides["com.app.types.ints"] = intArrayOf(9, 8)
        DebugInputRegistry.overrides["com.app.types.attempt"] = 7 to "backoff"

        val read = result.readAll()
        assertEquals(listOf("cdn.example.com"), read[10])
        assertContentEquals(intArrayOf(9, 8), read[13] as IntArray)
        assertEquals(7 to "backoff", read[21])
    }

    @Test
    fun `an enum override resolves to a constant`() {
        val result = compileEveryType()
        val tier = result.classLoader().loadClass("com.app.types.Tier")
        DebugInputRegistry.overrides["com.app.types.tier"] =
            tier.enumConstants.single { (it as Enum<*>).name == "TEAM" }

        assertEquals("TEAM", (result.readAll()[9] as Enum<*>).name)
    }

    private fun Map<String, com.rohittp.debuginput.DebugInputDescriptor>.specAndKey(
        name: String,
    ): Pair<String, String> = getValue(name).let { it.spec to it.typeKey }

    /** Declaration order, which is also the order [readAll] reads them in. */
    private fun specsByProperty(result: CompilationResult): Map<String, String> =
        result.descriptors(":types").associate { it.displayName to it.spec }

    @Suppress("UNCHECKED_CAST")
    private fun CompilationResult.readAll(): List<Any?> =
        classLoader().loadClass("com.app.types.TypesKt").getMethod("readAll").invoke(null)
            as List<Any?>

    private fun compileEveryType(): CompilationResult = compile(
        temp.newFolder(),
        SourceFile(
            "Types.kt",
            """
            package com.app.types

            import com.rohittp.debuginput.DebugInput

            enum class Tier { FREE, PRO, TEAM }

            @DebugInput val scalarInt: Int = 1
            @DebugInput val scalarLong: Long = 2L
            @DebugInput val scalarShort: Short = 3
            @DebugInput val scalarByte: Byte = 4
            @DebugInput val scalarFloat: Float = 5.5f
            @DebugInput val scalarDouble: Double = 6.5
            @DebugInput val scalarBoolean: Boolean = true
            @DebugInput val scalarChar: Char = 'x'
            @DebugInput val scalarString: String = "hi"
            @DebugInput val tier: Tier = Tier.FREE
            @DebugInput val hosts: List<String> = listOf("api.example.com")
            @DebugInput val ports: Set<Int> = setOf(80)
            @DebugInput val names: Array<String> = arrayOf("a")
            @DebugInput val ints: IntArray = intArrayOf(1)
            @DebugInput val longs: LongArray = longArrayOf(1L)
            @DebugInput val shorts: ShortArray = shortArrayOf(1)
            @DebugInput val bytes: ByteArray = byteArrayOf(1)
            @DebugInput val floats: FloatArray = floatArrayOf(1f)
            @DebugInput val doubles: DoubleArray = doubleArrayOf(1.0)
            @DebugInput val booleans: BooleanArray = booleanArrayOf(true)
            @DebugInput val chars: CharArray = charArrayOf('a')
            @DebugInput val attempt: Pair<Int, String> = 1 to "a"
            @DebugInput val window: Triple<Int, Int, Boolean> = Triple(1, 2, true)

            fun readAll(): List<Any?> = listOf(
                scalarInt, scalarLong, scalarShort, scalarByte, scalarFloat, scalarDouble,
                scalarBoolean, scalarChar, scalarString, tier, hosts, ports, names,
                ints, longs, shorts, bytes, floats, doubles, booleans, chars, attempt, window,
            )
            """.trimIndent(),
        ),
        module = ":types",
    ).assertSucceeded()
}
