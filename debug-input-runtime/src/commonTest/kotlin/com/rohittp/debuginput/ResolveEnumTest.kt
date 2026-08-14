package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An enum-typed input stores a constant name, and generated code passes the entry table
 * in because Kotlin/Native has no reflection to recover it.
 */
@OptIn(DebugInputInternalApi::class)
class ResolveEnumTest {

    @BeforeTest
    fun installOverrideStore() {
        installFakeOverrideStore()
    }

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    @Test
    fun `the resolved value is the default when no override is stored`() {
        assertEquals(Tier.FREE, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
    }

    @Test
    fun `a stored constant name resolves to that constant`() {
        for (tier in tierEntries) {
            installFakeOverrideStore(mapOf(SPEED to encoded(tier, "enm")))

            assertEquals(tier, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
        }
    }

    @Test
    fun `a name no constant answers to resolves to the default`() {
        // The constant was renamed or removed since the override was stored.
        installFakeOverrideStore(mapOf(SPEED to encoded("ENTERPRISE", "enm")))

        assertEquals(Tier.PRO, DebugInputRegistry.resolveEnum(SPEED, Tier.PRO, tierEntries))
    }

    @Test
    fun `an empty stored name resolves to the default`() {
        installFakeOverrideStore(mapOf(SPEED to "enm:0:"))

        assertEquals(Tier.PRO, DebugInputRegistry.resolveEnum(SPEED, Tier.PRO, tierEntries))
    }

    @Test
    fun `an override of another type is dormant for an enum input`() {
        installFakeOverrideStore(mapOf(SPEED to encoded("TEAM", "str")))

        assertEquals(Tier.FREE, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
    }

    @Test
    fun `an enum override set through the page resolves and persists across launches`() {
        val store = installFakeOverrideStore()

        DebugInputRegistry.setValue(SPEED, Tier.TEAM, "enm")
        assertEquals(Tier.TEAM, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
        assertEquals("enm:4:TEAM", store.persisted[SPEED])

        DebugInputRegistry.resetForTesting()
        assertEquals(Tier.TEAM, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
    }

    @Test
    fun `clearing an enum override restores the default`() {
        installFakeOverrideStore(mapOf(SPEED to encoded(Tier.TEAM, "enm")))
        assertEquals(Tier.TEAM, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))

        DebugInputRegistry.clearOverride(SPEED)

        assertEquals(Tier.FREE, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
    }

    @Test
    fun `an enum override is ignored in a release build`() {
        installFakeOverrideStore(mapOf(SPEED to encoded(Tier.TEAM, "enm")))
        installIsDebugBuildForTesting(false)

        assertEquals(Tier.FREE, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
    }

    @Test
    fun `the entry table generated code passes in is the one consulted`() {
        installFakeOverrideStore(mapOf(SPEED to encoded("PRO", "enm")))

        // A table without PRO in it cannot resolve PRO, however the enum is declared.
        assertEquals(
            Tier.FREE,
            DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, arrayOf(Tier.FREE, Tier.TEAM)),
        )
        assertEquals(Tier.PRO, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
    }
}
