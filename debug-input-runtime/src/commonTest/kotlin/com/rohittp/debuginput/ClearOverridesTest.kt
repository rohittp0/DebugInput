package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-row reset and **Reset all**, from the page's point of view. */
@OptIn(DebugInputInternalApi::class)
class ClearOverridesTest {

    private lateinit var store: FakeOverrideStore

    @BeforeTest
    fun installOverrideStore() {
        store = installFakeOverrideStore(
            mapOf(
                SPEED to encodeInt(25),
                TIMEOUT to encodeInt(90),
                FREE_LIMIT to encodeInt(3),
            ),
        )
    }

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    @Test
    fun `clearOverride restores the default for that id`() {
        DebugInputRegistry.clearOverride(SPEED)

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertNull(DebugInputRegistry.overrideOf(SPEED))
    }

    @Test
    fun `clearOverride leaves the other ids resolving their overrides`() {
        DebugInputRegistry.clearOverride(SPEED)

        assertEquals(90, DebugInputRegistry.resolveInt(TIMEOUT, 30))
        assertEquals(3, DebugInputRegistry.resolveInt(FREE_LIMIT, 1))
    }

    @Test
    fun `clearOverride removes only that id from the override store`() {
        DebugInputRegistry.clearOverride(SPEED)

        assertEquals(setOf(TIMEOUT, FREE_LIMIT), store.persisted.keys)
    }

    @Test
    fun `a cleared override stays cleared across a fresh hydration`() {
        DebugInputRegistry.clearOverride(SPEED)
        DebugInputRegistry.resetForTesting()

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(90, DebugInputRegistry.resolveInt(TIMEOUT, 30))
    }

    @Test
    fun `clearOverride on an id with no override changes nothing`() {
        DebugInputRegistry.clearOverride("com.app.physics.gravity")

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(setOf(SPEED, TIMEOUT, FREE_LIMIT), store.persisted.keys)
    }

    @Test
    fun `clearAll restores every default`() {
        DebugInputRegistry.clearAll()

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(30, DebugInputRegistry.resolveInt(TIMEOUT, 30))
        assertEquals(1, DebugInputRegistry.resolveInt(FREE_LIMIT, 1))
    }

    @Test
    fun `clearAll empties the override store so every default survives a fresh hydration`() {
        DebugInputRegistry.clearAll()

        assertTrue(store.persisted.isEmpty(), "override store still holds ${store.persisted}")

        DebugInputRegistry.resetForTesting()
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(30, DebugInputRegistry.resolveInt(TIMEOUT, 30))
    }

    @Test
    fun `an override set after clearAll resolves again`() {
        DebugInputRegistry.clearAll()
        DebugInputRegistry.setInt(SPEED, 40)

        assertEquals(40, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(30, DebugInputRegistry.resolveInt(TIMEOUT, 30))
    }
}
