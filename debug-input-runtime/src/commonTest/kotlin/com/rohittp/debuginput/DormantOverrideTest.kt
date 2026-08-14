package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A dormant override is an override whose id currently matches no input: ignored,
 * never deleted, and applied again if an input later claims that id. Required
 * behaviour, not an accident — docs/adr/0005-id-derivation-and-dormant-overrides.md.
 */
@OptIn(DebugInputInternalApi::class)
class DormantOverrideTest {

    /** The id `speed` had before someone renamed the property. */
    private val abandonedId = "com.app.physics.velocity"

    private lateinit var store: FakeOverrideStore

    @BeforeTest
    fun installOverrideStore() {
        store = installFakeOverrideStore(
            mapOf(abandonedId to encodeInt(25), SPEED to encodeInt(40)),
        )
    }

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    @Test
    fun `an override no input claims is ignored and stays in the override store`() {
        assertEquals(10, DebugInputRegistry.resolveInt(TIMEOUT, 10))

        assertEquals(encodeInt(25), store.persisted[abandonedId], "dormant, not deleted")
    }

    @Test
    fun `a dormant override applies again once an input claims its id`() {
        // Nothing reads the abandoned id: the input that used to own it was renamed.
        assertEquals(40, DebugInputRegistry.resolveInt(SPEED, 10))

        // The property is renamed back, so a read arrives for the abandoned id again.
        assertEquals(25, DebugInputRegistry.resolveInt(abandonedId, 10))
    }

    @Test
    fun `a dormant override survives writes and clears of every other id`() {
        DebugInputRegistry.setInt(TIMEOUT, 90)
        DebugInputRegistry.clearOverride(SPEED)

        assertEquals(encodeInt(25), store.persisted[abandonedId])
        assertEquals(25, DebugInputRegistry.resolveInt(abandonedId, 10))
    }

    @Test
    fun `clearAll is the only way to be rid of a dormant override`() {
        DebugInputRegistry.clearAll()

        assertTrue(store.persisted.isEmpty(), "override store still holds ${store.persisted}")
        assertEquals(10, DebugInputRegistry.resolveInt(abandonedId, 10))
    }

    @Test
    fun `a dormant override left by a different type is ignored rather than a crash`() {
        // The id was claimed by a Boolean input in an earlier build of the app.
        store = installFakeOverrideStore(mapOf(SPEED to "bln:4:true"))

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals("bln:4:true", store.persisted[SPEED], "dormant, not deleted")
    }

    @Test
    fun `a dormant override of another type keeps being ignored across a relaunch`() {
        store = installFakeOverrideStore(mapOf(SPEED to "str:4:fast"))

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        DebugInputRegistry.resetForTesting()
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals("str:4:fast", store.persisted[SPEED])
    }
}
