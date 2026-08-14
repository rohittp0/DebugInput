package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The write the page makes for every type that is not the Int [DebugInputRegistry.setInt] covers. */
@OptIn(DebugInputInternalApi::class)
class SetValueTest {

    private lateinit var store: FakeOverrideStore

    @BeforeTest
    fun installOverrideStore() {
        store = installFakeOverrideStore()
    }

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    @Test
    fun `setValue with an Int spec writes exactly what setInt writes`() {
        DebugInputRegistry.setValue(SPEED, 25, "int")

        assertEquals(encodeInt(25), store.persisted[SPEED])
        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    @Test
    fun `every scalar set through setValue resolves through its own resolver`() {
        DebugInputRegistry.setValue(SPEED, 9L, "lng")
        assertEquals(9L, DebugInputRegistry.resolveLong(SPEED, 1L))

        DebugInputRegistry.setValue(SPEED, (-3).toShort(), "sht")
        assertEquals((-3).toShort(), DebugInputRegistry.resolveShort(SPEED, 1))

        DebugInputRegistry.setValue(SPEED, 7.toByte(), "byt")
        assertEquals(7.toByte(), DebugInputRegistry.resolveByte(SPEED, 1))

        DebugInputRegistry.setValue(SPEED, 2.5f, "flt")
        assertEquals(2.5f, DebugInputRegistry.resolveFloat(SPEED, 1.0f))

        DebugInputRegistry.setValue(SPEED, Double.NaN, "dbl")
        assertTrue(DebugInputRegistry.resolveDouble(SPEED, 1.0).isNaN())

        DebugInputRegistry.setValue(SPEED, false, "bln")
        assertEquals(false, DebugInputRegistry.resolveBoolean(SPEED, true))

        DebugInputRegistry.setValue(SPEED, ':', "chr")
        assertEquals(':', DebugInputRegistry.resolveChar(SPEED, 'z'))

        DebugInputRegistry.setValue(SPEED, "a:b", "str")
        assertEquals("a:b", DebugInputRegistry.resolveString(SPEED, "d"))
    }

    @Test
    fun `every composite set through setValue resolves back to the same value`() {
        for ((literal, value) in COMPOSITES) {
            store = installFakeOverrideStore()

            DebugInputRegistry.setValue(SPEED, value, literal)

            val resolved = DebugInputRegistry.resolveComposite(SPEED, null, literal)
            assertTrue(sameValue(value, resolved), "$literal set to $value resolved as $resolved")
        }
    }

    @Test
    fun `a value set through setValue persists across launches`() {
        for ((literal, value) in COMPOSITES) {
            store = installFakeOverrideStore()

            DebugInputRegistry.setValue(SPEED, value, literal)
            DebugInputRegistry.resetForTesting()

            val resolved = DebugInputRegistry.resolveComposite(SPEED, null, literal)
            assertTrue(sameValue(value, resolved), "$literal did not survive a relaunch")
        }
    }

    @Test
    fun `setValue writes the wire form the codec produces`() {
        DebugInputRegistry.setValue(SPEED, listOf("api.example.com"), "lst<str>")

        assertEquals("lst:22:str:15:api.example.com", store.persisted[SPEED])
    }

    @Test
    fun `a value that is not the shape the spec describes is refused rather than stored`() {
        for ((literal, value) in listOf<Pair<String, Any?>>(
            "int" to "25",
            "int" to 25L,
            "str" to 25,
            "bln" to "true",
            "chr" to "ab",
            "lst<str>" to listOf(1, 2),
            "lst<str>" to setOf("a"),
            "iarr" to longArrayOf(1L),
            "arr<str>" to arrayOf(1),
            "pair<int,str>" to Pair("a", 1),
            "trip<int,int,bln>" to Pair(1, 2),
            "int" to null,
            "lst<str>" to null,
        )) {
            store = installFakeOverrideStore()

            DebugInputRegistry.setValue(SPEED, value, literal)

            assertNull(store.persisted[SPEED], "$value was stored as $literal")
        }
    }

    @Test
    fun `a spec literal that does not parse stores nothing`() {
        for (literal in listOf("", "unknown", "lst", "lst<lst<int>>", "lst<enm>")) {
            store = installFakeOverrideStore()

            DebugInputRegistry.setValue(SPEED, listOf("api"), literal)

            assertNull(store.persisted[SPEED], "stored under spec '$literal'")
        }
    }

    @Test
    fun `setValue replaces an override of another type on the same id`() {
        store = installFakeOverrideStore(mapOf(SPEED to encoded(25, "int")))

        DebugInputRegistry.setValue(SPEED, listOf("api"), "lst<str>")

        assertEquals(encoded(listOf("api"), "lst<str>"), store.persisted[SPEED])
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10), "the Int reading is dormant now")
    }

    @Test
    fun `a listener hears the value setValue was given`() {
        val seen = mutableListOf<Any?>()
        DebugInputRegistry.addListener(SPEED) { seen += it }

        DebugInputRegistry.setValue(SPEED, listOf("api"), "lst<str>")
        DebugInputRegistry.setValue(SPEED, listOf("cdn"), "lst<str>")

        assertEquals(listOf<Any?>(listOf("api"), listOf("cdn")), seen)
    }

    @Test
    fun `a refused write tells no listener`() {
        val seen = mutableListOf<Any?>()
        DebugInputRegistry.addListener(SPEED) { seen += it }

        DebugInputRegistry.setValue(SPEED, "not an Int", "int")
        DebugInputRegistry.setValue(SPEED, 25, "nonsense")

        assertTrue(seen.isEmpty(), "a write that did not happen was announced as $seen")
    }

    @Test
    fun `setValue is dropped while the override store is unavailable`() {
        makeOverrideStoreUnavailable()

        DebugInputRegistry.setValue(SPEED, listOf("api"), "lst<str>")

        assertEquals(
            emptyList<String>(),
            DebugInputRegistry.resolveComposite(SPEED, emptyList<String>(), "lst<str>"),
        )
    }

    @Test
    fun `clearing a composite override restores the default`() {
        store = installFakeOverrideStore()
        val default = listOf("localhost")
        DebugInputRegistry.setValue(SPEED, listOf("api"), "lst<str>")

        DebugInputRegistry.clearAll()

        assertEquals(default, DebugInputRegistry.resolveComposite(SPEED, default, "lst<str>"))
        assertTrue(store.persisted.isEmpty())
    }

    private companion object {
        val COMPOSITES: List<Pair<String, Any>> = listOf(
            "lst<str>" to listOf("api.example.com", "a:b", ""),
            "lst<int>" to listOf(1, -2, Int.MIN_VALUE),
            "lst<str>" to emptyList<String>(),
            "set<int>" to setOf(1, 2, 3),
            "set<str>" to emptySet<String>(),
            "arr<str>" to arrayOf("one", "two"),
            "arr<int>" to emptyArray<Int>(),
            "iarr" to intArrayOf(1, 2, 3),
            "larr" to longArrayOf(Long.MAX_VALUE),
            "sarr" to shortArrayOf(-1),
            "barr" to byteArrayOf(0),
            "farr" to floatArrayOf(Float.NaN, -0.0f),
            "darr" to doubleArrayOf(1.5, Double.NEGATIVE_INFINITY),
            "zarr" to booleanArrayOf(true, false),
            "carr" to charArrayOf(':', 'x'),
            "pair<int,str>" to Pair(3, "backoff"),
            "trip<int,int,bln>" to Triple(1, 2, true),
        )
    }
}
