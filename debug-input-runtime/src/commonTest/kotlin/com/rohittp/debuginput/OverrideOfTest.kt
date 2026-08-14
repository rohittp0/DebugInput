package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the page reads to know whether a row is changed. It is handed an id and nothing
 * else, so it answers with whatever the stored bytes say they are — which is not always
 * the type the input asking for them has.
 */
@OptIn(DebugInputInternalApi::class)
class OverrideOfTest {

    @BeforeTest
    fun installOverrideStore() {
        installFakeOverrideStore()
    }

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    @Test
    fun `overrideOf reports null while an input is at its default`() {
        assertNull(DebugInputRegistry.overrideOf(SPEED))
    }

    @Test
    fun `overrideOf reports the stored override of every scalar type`() {
        installFakeOverrideStore(
            mapOf(
                "a" to encoded(25, "int"),
                "b" to encoded(9L, "lng"),
                "c" to encoded(2.5, "dbl"),
                "d" to encoded(true, "bln"),
                "e" to encoded("a:b", "str"),
                "f" to encoded(':', "chr"),
                "g" to encoded(Tier.PRO, "enm"),
            ),
        )

        assertEquals(25, DebugInputRegistry.overrideOf("a"))
        assertEquals(9L, DebugInputRegistry.overrideOf("b"))
        assertEquals(2.5, DebugInputRegistry.overrideOf("c"))
        assertEquals(true, DebugInputRegistry.overrideOf("d"))
        assertEquals("a:b", DebugInputRegistry.overrideOf("e"))
        assertEquals(':', DebugInputRegistry.overrideOf("f"))
        assertEquals("PRO", DebugInputRegistry.overrideOf("g"), "an enum reads as its name")
    }

    @Test
    fun `overrideOf reports the elements of a stored container`() {
        installFakeOverrideStore(
            mapOf(
                "a" to encoded(listOf("api", "cdn"), "lst<str>"),
                "b" to encoded(setOf(1, 2), "set<int>"),
                "c" to encoded(Pair(3, "backoff"), "pair<int,str>"),
                "d" to encoded(intArrayOf(1, 2), "iarr"),
            ),
        )

        assertEquals(listOf("api", "cdn"), DebugInputRegistry.overrideOf("a"))
        assertEquals(setOf(1, 2), DebugInputRegistry.overrideOf("b"))
        assertEquals(Pair(3, "backoff"), DebugInputRegistry.overrideOf("c"))
        assertTrue(intArrayOf(1, 2) contentEquals DebugInputRegistry.overrideOf("d") as IntArray)
    }

    @Test
    fun `overrideOf reports null for a stored value that cannot be read at all`() {
        for (stored in MALFORMED_ENCODINGS) {
            installFakeOverrideStore(mapOf(SPEED to stored))

            assertNull(DebugInputRegistry.overrideOf(SPEED), "stored '$stored'")
        }
    }

    @Test
    fun `overrideOf reports a dormant override of another type as that other type`() {
        // The page is asked for an id, not for a type, so a dormant Boolean on what is now
        // an Int input reads back as a Boolean while every read of the input resolves its
        // default. The page has the descriptor and has to be the one to reconcile them.
        installFakeOverrideStore(mapOf(SPEED to encoded(true, "bln")))

        assertEquals(true, DebugInputRegistry.overrideOf(SPEED))
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
    }
}
