package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The write path the page drives, and what it means for an override to persist. */
@OptIn(DebugInputInternalApi::class)
class SetIntTest {

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
    fun `setInt makes the resolved value the new override`() {
        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(25, DebugInputRegistry.overrideOf(SPEED))
    }

    @Test
    fun `setInt writes the encoded override to the override store`() {
        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(encodeInt(25), store.persisted[SPEED])
    }

    @Test
    fun `an override set before a fresh hydration persists across launches`() {
        DebugInputRegistry.setInt(SPEED, 25)
        val loadsBeforeRelaunch = store.loads

        // The process restarts: the registry forgets everything, the store does not.
        DebugInputRegistry.resetForTesting()

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(
            loadsBeforeRelaunch + 1,
            store.loads,
            "the relaunched process hydrates from the override store again",
        )
    }

    @Test
    fun `setInt replaces an existing override for the same id`() {
        DebugInputRegistry.setInt(SPEED, 25)
        DebugInputRegistry.setInt(SPEED, 40)

        assertEquals(40, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(encodeInt(40), store.persisted[SPEED])
    }

    @Test
    fun `setInt leaves the other ids resolving what they resolved before`() {
        store = installFakeOverrideStore(mapOf(TIMEOUT to encodeInt(90)))

        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(90, DebugInputRegistry.resolveInt(TIMEOUT, 30))
        assertEquals(1, DebugInputRegistry.resolveInt(FREE_LIMIT, 1))
        assertEquals(setOf(SPEED, TIMEOUT), store.persisted.keys)
    }

    @Test
    fun `setInt overwrites a dormant override of another type that held the id`() {
        store = installFakeOverrideStore(mapOf(SPEED to "bln:4:true"))
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))

        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(encodeInt(25), store.persisted[SPEED])
    }

    @Test
    fun `an override at the extremes of Int persists across a fresh hydration`() {
        for (value in listOf(0, -25, Int.MIN_VALUE, Int.MAX_VALUE)) {
            store = installFakeOverrideStore()

            DebugInputRegistry.setInt(SPEED, value)
            DebugInputRegistry.resetForTesting()

            assertEquals(value, DebugInputRegistry.resolveInt(SPEED, 10), "override $value")
        }
    }
}
