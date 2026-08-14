package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The read path: what a rewritten getter returns. */
@OptIn(DebugInputInternalApi::class)
class ResolveIntTest {

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
    fun `the registry reports a debug build so reads resolve through it`() {
        assertTrue(DebugInputRegistry.isDebugBuild)
    }

    @Test
    fun `the resolved value is the default when no override is stored`() {
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(0, DebugInputRegistry.resolveInt(TIMEOUT, 0))
        assertEquals(-7, DebugInputRegistry.resolveInt(FREE_LIMIT, -7))
    }

    @Test
    fun `the resolved value is the override when one is stored for the id`() {
        store = installFakeOverrideStore(mapOf(SPEED to encodeInt(25)))

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    @Test
    fun `each id resolves its own override and ids without one resolve their default`() {
        store = installFakeOverrideStore(
            mapOf(SPEED to encodeInt(25), FREE_LIMIT to encodeInt(3)),
        )

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(3, DebugInputRegistry.resolveInt(FREE_LIMIT, 1))
        assertEquals(30, DebugInputRegistry.resolveInt(TIMEOUT, 30))
    }

    @Test
    fun `an override at the extremes of Int resolves and is not mistaken for malformed`() {
        for (value in listOf(0, -1, -25, Int.MAX_VALUE, Int.MIN_VALUE)) {
            store = installFakeOverrideStore(mapOf(SPEED to encodeInt(value)))

            assertEquals(value, DebugInputRegistry.resolveInt(SPEED, 10), "override $value")
        }
    }

    @Test
    fun `a stored override whose type tag is not Int is ignored and the default resolves`() {
        // A dormant override written by a different type that once held this id.
        for (encoded in OTHER_TYPE_TAGS) {
            store = installFakeOverrideStore(mapOf(SPEED to encoded))

            assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10), "stored '$encoded'")
        }
    }

    @Test
    fun `a malformed encoded override resolves to the default and never throws`() {
        for (encoded in MALFORMED_ENCODINGS) {
            store = installFakeOverrideStore(mapOf(SPEED to encoded))

            assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10), "stored '$encoded'")
        }
    }

    @Test
    fun `an unreadable override is left in the override store and is not repaired or deleted`() {
        store = installFakeOverrideStore(mapOf(SPEED to "int:12:not-a-number"))

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals("int:12:not-a-number", store.persisted[SPEED])
    }

    @Test
    fun `overrideOf reports the override and reports null while the input is at its default`() {
        store = installFakeOverrideStore(
            mapOf(SPEED to encodeInt(25), TIMEOUT to "int:1:25"),
        )

        assertEquals(25, DebugInputRegistry.overrideOf(SPEED))
        assertEquals(null, DebugInputRegistry.overrideOf(FREE_LIMIT))
        // An override the page cannot decode at all reads as "at its default", not as
        // garbage. A dormant override of another *readable* type is a different matter —
        // see OverrideOfTest.
        assertEquals(null, DebugInputRegistry.overrideOf(TIMEOUT))
    }
}
