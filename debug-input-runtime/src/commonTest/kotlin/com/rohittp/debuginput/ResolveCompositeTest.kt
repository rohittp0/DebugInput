package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The one generic entry point every container, array and tuple read goes through. The
 * spec literal is baked in at the call site and the caller casts the result, so what
 * comes back has to be the exact shape the spec describes.
 */
@OptIn(DebugInputInternalApi::class)
class ResolveCompositeTest {

    @BeforeTest
    fun installOverrideStore() {
        installFakeOverrideStore()
    }

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    @Test
    fun `a collection override resolves and its default stands when nothing is stored`() {
        val default = listOf("localhost")
        assertSame(default, DebugInputRegistry.resolveComposite(SPEED, default, "lst<str>"))

        val hosts = listOf("api.example.com", "cdn.example.com")
        installFakeOverrideStore(mapOf(SPEED to encoded(hosts, "lst<str>")))

        assertEquals(hosts, DebugInputRegistry.resolveComposite(SPEED, default, "lst<str>"))
    }

    @Test
    fun `a Set override resolves as a Set`() {
        installFakeOverrideStore(mapOf(SPEED to encoded(setOf(1, 2, 3), "set<int>")))

        assertEquals(
            setOf(1, 2, 3),
            DebugInputRegistry.resolveComposite(SPEED, setOf(0), "set<int>"),
        )
    }

    @Test
    fun `a tuple override resolves component by component`() {
        installFakeOverrideStore(
            mapOf(
                SPEED to encoded(Pair(3, "backoff"), "pair<int,str>"),
                TIMEOUT to encoded(Triple(1, 2, true), "trip<int,int,bln>"),
            ),
        )

        assertEquals(
            Pair(3, "backoff"),
            DebugInputRegistry.resolveComposite(SPEED, Pair(0, ""), "pair<int,str>"),
        )
        assertEquals(
            Triple(1, 2, true),
            DebugInputRegistry.resolveComposite(TIMEOUT, Triple(0, 0, false), "trip<int,int,bln>"),
        )
    }

    @Test
    fun `every primitive array override resolves as its own array type`() {
        for ((literal, value) in PRIMITIVE_ARRAYS) {
            installFakeOverrideStore(mapOf(SPEED to encoded(value, literal)))

            val resolved = DebugInputRegistry.resolveComposite(SPEED, emptyOf(literal), literal)
            assertTrue(sameValue(value, resolved), "$literal resolved as $resolved")
        }
    }

    @Test
    fun `an object array override resolves as an array of the element type`() {
        installFakeOverrideStore(mapOf(SPEED to encoded(arrayOf("one", "two"), "arr<str>")))

        val resolved = DebugInputRegistry.resolveComposite(SPEED, emptyArray<String>(), "arr<str>")

        @Suppress("UNCHECKED_CAST")
        val typed = resolved as Array<String>
        assertEquals(listOf("one", "two"), typed.toList())
    }

    @Test
    fun `an empty container override resolves as an empty container`() {
        installFakeOverrideStore(
            mapOf(
                SPEED to encoded(emptyList<String>(), "lst<str>"),
                TIMEOUT to encoded(intArrayOf(), "iarr"),
                FREE_LIMIT to encoded(emptyArray<String>(), "arr<str>"),
            ),
        )

        assertEquals(
            emptyList<String>(),
            DebugInputRegistry.resolveComposite(SPEED, listOf("x"), "lst<str>"),
        )
        assertTrue(
            (DebugInputRegistry.resolveComposite(TIMEOUT, intArrayOf(1), "iarr") as IntArray)
                .isEmpty(),
        )

        @Suppress("UNCHECKED_CAST")
        val emptyStrings =
            DebugInputRegistry.resolveComposite(FREE_LIMIT, arrayOf("x"), "arr<str>")
                as Array<String>
        assertTrue(emptyStrings.isEmpty())
    }

    @Test
    fun `elements containing the separator survive the round trip through the store`() {
        val hosts = listOf("a:1", "str:3:xyz", ":", "")
        installFakeOverrideStore(mapOf(SPEED to encoded(hosts, "lst<str>")))

        assertEquals(hosts, DebugInputRegistry.resolveComposite(SPEED, emptyList<String>(), "lst<str>"))
    }

    @Test
    fun `a stored override of another shape is dormant and the default instance stands`() {
        val default = listOf("localhost")
        for (stored in listOf(
            encoded(listOf(1), "lst<int>"),
            encoded(setOf("a"), "set<str>"),
            encoded(arrayOf("a"), "arr<str>"),
            encoded(25, "int"),
            encoded(intArrayOf(1), "iarr"),
        )) {
            installFakeOverrideStore(mapOf(SPEED to stored))

            assertSame(
                default,
                DebugInputRegistry.resolveComposite(SPEED, default, "lst<str>"),
                "stored '$stored'",
            )
        }
    }

    @Test
    fun `a malformed override leaves the default instance in place`() {
        val default = intArrayOf(1, 2)
        for (stored in MALFORMED_ENCODINGS) {
            installFakeOverrideStore(mapOf(SPEED to stored))

            assertSame(
                default,
                DebugInputRegistry.resolveComposite(SPEED, default, "iarr"),
                "stored '$stored'",
            )
        }
    }

    @Test
    fun `a spec literal that does not parse leaves the default in place`() {
        val default = listOf("localhost")
        installFakeOverrideStore(mapOf(SPEED to encoded(listOf("api"), "lst<str>")))

        for (literal in listOf("lst<lst<str>>", "lst", "", "unknown", "lst<enm>")) {
            assertSame(
                default,
                DebugInputRegistry.resolveComposite(SPEED, default, literal),
                "spec '$literal'",
            )
        }
    }

    @Test
    fun `a composite override is ignored in a release build`() {
        val default = listOf("localhost")
        installFakeOverrideStore(mapOf(SPEED to encoded(listOf("api"), "lst<str>")))
        assertEquals(listOf("api"), DebugInputRegistry.resolveComposite(SPEED, default, "lst<str>"))

        installIsDebugBuildForTesting(false)

        assertSame(default, DebugInputRegistry.resolveComposite(SPEED, default, "lst<str>"))
    }

    @Test
    fun `a composite override persists across launches`() {
        val store = installFakeOverrideStore()

        DebugInputRegistry.setValue(SPEED, listOf("api.example.com"), "lst<str>")
        DebugInputRegistry.resetForTesting()

        assertEquals(
            listOf("api.example.com"),
            DebugInputRegistry.resolveComposite(SPEED, emptyList<String>(), "lst<str>"),
        )
        assertEquals(encoded(listOf("api.example.com"), "lst<str>"), store.persisted[SPEED])
    }

    private fun emptyOf(literal: String): Any = when (literal) {
        "iarr" -> intArrayOf()
        "larr" -> longArrayOf()
        "sarr" -> shortArrayOf()
        "barr" -> byteArrayOf()
        "farr" -> floatArrayOf()
        "darr" -> doubleArrayOf()
        "zarr" -> booleanArrayOf()
        else -> charArrayOf()
    }

    private companion object {
        val PRIMITIVE_ARRAYS: List<Pair<String, Any>> = listOf(
            "iarr" to intArrayOf(1, -2, Int.MAX_VALUE),
            "larr" to longArrayOf(Long.MIN_VALUE, 0L),
            "sarr" to shortArrayOf(1, Short.MIN_VALUE),
            "barr" to byteArrayOf(-1, Byte.MAX_VALUE),
            "farr" to floatArrayOf(1.5f, Float.NaN, -0.0f),
            "darr" to doubleArrayOf(2.5, Double.POSITIVE_INFINITY),
            "zarr" to booleanArrayOf(true, false),
            "carr" to charArrayOf('a', ':'),
        )
    }
}
