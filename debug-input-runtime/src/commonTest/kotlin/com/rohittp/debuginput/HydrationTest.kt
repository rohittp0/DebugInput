package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When the registry reads the override store, and what it does while there is no
 * store to read — on Android, before the `ContentProvider` has captured a `Context`.
 */
@OptIn(DebugInputInternalApi::class)
class HydrationTest {

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    @Test
    fun `the override store is not loaded until the first read`() {
        val store = installFakeOverrideStore(mapOf(SPEED to encodeInt(25)))

        assertEquals(0, store.loads)

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(1, store.loads)
    }

    @Test
    fun `repeated reads hydrate from the override store only once`() {
        val store = installFakeOverrideStore(mapOf(SPEED to encodeInt(25)))

        repeat(5) { assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10)) }
        repeat(5) { assertEquals(30, DebugInputRegistry.resolveInt(TIMEOUT, 30)) }
        DebugInputRegistry.overrideOf(SPEED)

        assertEquals(1, store.loads, "hydrated ${store.loads} times")
    }

    @Test
    fun `a read hydrates again after resetForTesting the way a relaunch does`() {
        val store = installFakeOverrideStore(mapOf(SPEED to encodeInt(25)))

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        DebugInputRegistry.resetForTesting()
        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))

        assertEquals(2, store.loads)
    }

    @Test
    fun `reads resolve defaults while the override store is unavailable`() {
        makeOverrideStoreUnavailable()

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(30, DebugInputRegistry.resolveInt(TIMEOUT, 30))
        assertEquals(null, DebugInputRegistry.overrideOf(SPEED))
    }

    @Test
    fun `a later read hydrates once the override store becomes available`() {
        // The read that arrived too early must not cache an empty map forever.
        makeOverrideStoreUnavailable()
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))

        val store = FakeOverrideStore(mapOf(SPEED to encodeInt(25)))
        installOverrideStoreForTesting(store)

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(1, store.loads)
    }

    @Test
    fun `many reads before the override store exists all resolve defaults and none stick`() {
        makeOverrideStoreUnavailable()
        repeat(5) { assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10)) }

        val store = FakeOverrideStore(mapOf(SPEED to encodeInt(25)))
        installOverrideStoreForTesting(store)

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    @Test
    fun `an override cannot be set while the override store is unavailable`() {
        // Nothing to persist into, so the write is dropped rather than half-applied.
        makeOverrideStoreUnavailable()
        val seen = mutableListOf<Any?>()
        DebugInputRegistry.addListener(SPEED) { seen += it }

        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertTrue(seen.isEmpty(), "a dropped write must not tell listeners it happened")
    }
}
