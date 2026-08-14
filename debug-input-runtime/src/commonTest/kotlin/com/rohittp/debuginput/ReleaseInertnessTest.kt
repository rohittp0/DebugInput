package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Inertness in a release build is the whole promise of this library, and on both
 * platforms it is a runtime property rather than an absence of code — see the amendment
 * in docs/adr/0002-android-release-skips-the-transform.md. So it is asserted here rather
 * than by scanning bytecode.
 */
@OptIn(DebugInputInternalApi::class)
class ReleaseInertnessTest {

    @AfterTest
    fun tearDown() {
        restorePlatformOverrideStore()
    }

    @Test
    fun `a stored override is ignored in a release build`() {
        installFakeOverrideStore(mapOf(SPEED to encodeInt(25)))
        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, default = 10))

        installIsDebugBuildForTesting(false)

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, default = 10))
    }

    @Test
    fun `a release build reports itself as one`() {
        installFakeOverrideStore()
        installIsDebugBuildForTesting(false)

        assertFalse(DebugInputRegistry.isDebugBuild)
    }

    @Test
    fun `the override survives being ignored and applies again in a debug build`() {
        val store = installFakeOverrideStore(mapOf(SPEED to encodeInt(25)))

        installIsDebugBuildForTesting(false)
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, default = 10))

        // Nothing was deleted on the way through — a release build reads past
        // overrides, it does not prune them.
        assertEquals(encodeInt(25), store.persisted[SPEED])

        installIsDebugBuildForTesting(true)
        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, default = 10))
    }
}
