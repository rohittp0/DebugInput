package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Arrays are mutable, so what a getter hands back is observable in ways a scalar's value
 * is not. See docs/adr/0009-array-inputs-return-a-cached-instance.md.
 */
@OptIn(DebugInputInternalApi::class)
class ArrayIdentityTest {

    private lateinit var store: FakeOverrideStore

    @BeforeTest
    fun installOverrideStore() {
        store = installFakeOverrideStore()
    }

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    private fun weights(default: DoubleArray): Any? =
        DebugInputRegistry.resolveComposite(SPEED, default, "darr")

    @Test
    fun `with no override stored a read returns the default array instance itself`() {
        val default = doubleArrayOf(1.0, 2.0)

        assertSame(default, weights(default))
        assertSame(default, weights(default))
    }

    @Test
    fun `an in place mutation of an unoverridden array sticks exactly as it does without the plugin`() {
        val default = doubleArrayOf(1.0, 2.0)

        (weights(default) as DoubleArray)[0] = 5.0

        assertEquals(5.0, (weights(default) as DoubleArray)[0])
        assertEquals(5.0, default[0])
    }

    @Test
    fun `with an override stored every read returns the same decoded instance`() {
        val default = doubleArrayOf(1.0, 2.0)
        store = installFakeOverrideStore(mapOf(SPEED to encoded(doubleArrayOf(3.0, 4.0), "darr")))

        val first = weights(default)
        val second = weights(default)

        assertSame(first, second)
        assertNotSame(default, first)
        assertTrue(doubleArrayOf(3.0, 4.0) contentEquals first as DoubleArray)
    }

    @Test
    fun `mutating an overridden array is visible to later reads in this process`() {
        val default = doubleArrayOf(1.0, 2.0)
        store = installFakeOverrideStore(mapOf(SPEED to encoded(doubleArrayOf(3.0, 4.0), "darr")))

        (weights(default) as DoubleArray)[0] = 9.0

        assertEquals(9.0, (weights(default) as DoubleArray)[0])
    }

    @Test
    fun `mutating an overridden array does not write back to the override store`() {
        val default = doubleArrayOf(1.0, 2.0)
        val stored = encoded(doubleArrayOf(3.0, 4.0), "darr")
        store = installFakeOverrideStore(mapOf(SPEED to stored))

        (weights(default) as DoubleArray)[0] = 9.0

        assertEquals(stored, store.persisted[SPEED], "the store is not written by a read")
        // And a relaunch reads what was stored, not what was mutated.
        DebugInputRegistry.resetForTesting()
        assertEquals(3.0, (weights(default) as DoubleArray)[0])
    }

    @Test
    fun `an edit swaps the instance and the next read sees the new one`() {
        val default = doubleArrayOf(1.0, 2.0)
        store = installFakeOverrideStore(mapOf(SPEED to encoded(doubleArrayOf(3.0, 4.0), "darr")))
        val before = weights(default) as DoubleArray

        DebugInputRegistry.setValue(SPEED, doubleArrayOf(7.0, 8.0), "darr")
        val after = weights(default)

        assertNotSame(before, after)
        assertTrue(doubleArrayOf(7.0, 8.0) contentEquals after as DoubleArray)
        // Whatever held the previous instance keeps it, unchanged.
        assertTrue(doubleArrayOf(3.0, 4.0) contentEquals before)
    }

    @Test
    fun `clearAll swaps back to the default instance`() {
        val default = doubleArrayOf(1.0, 2.0)
        store = installFakeOverrideStore(mapOf(SPEED to encoded(doubleArrayOf(3.0, 4.0), "darr")))
        val overridden = weights(default)
        assertNotSame(default, overridden)

        DebugInputRegistry.clearAll()

        assertSame(default, weights(default))
    }

    @Test
    fun `clearing one override swaps that array back to its default instance`() {
        val default = doubleArrayOf(1.0, 2.0)
        store = installFakeOverrideStore(mapOf(SPEED to encoded(doubleArrayOf(3.0, 4.0), "darr")))
        assertNotSame(default, weights(default))

        DebugInputRegistry.clearOverride(SPEED)

        assertSame(default, weights(default))
    }

    @Test
    fun `an override set after clearAll is decoded fresh rather than served from the old cache`() {
        val default = doubleArrayOf(1.0, 2.0)
        store = installFakeOverrideStore(mapOf(SPEED to encoded(doubleArrayOf(3.0, 4.0), "darr")))
        (weights(default) as DoubleArray)[0] = 9.0

        DebugInputRegistry.clearAll()
        DebugInputRegistry.setValue(SPEED, doubleArrayOf(3.0, 4.0), "darr")

        // The same bytes as before, so a cache keyed on them would hand back the mutated
        // instance. The stored override is 3.0, so the read must be 3.0.
        assertEquals(3.0, (weights(default) as DoubleArray)[0])
    }

    @Test
    fun `a fresh hydration swaps the instance`() {
        val default = doubleArrayOf(1.0, 2.0)
        store = installFakeOverrideStore(mapOf(SPEED to encoded(doubleArrayOf(3.0, 4.0), "darr")))
        val before = weights(default)

        DebugInputRegistry.resetForTesting()

        assertNotSame(before, weights(default))
    }

    @Test
    fun `editing one array input swaps every array instance because the override map is replaced`() {
        // ADR-0009 invalidates on any change to the override map rather than per id. The
        // coarseness is deliberate: no bookkeeping, and no way to hand back an instance
        // decoded from an override that is no longer stored.
        val speedDefault = doubleArrayOf(1.0)
        val timeoutDefault = intArrayOf(1)
        store = installFakeOverrideStore(
            mapOf(
                SPEED to encoded(doubleArrayOf(3.0), "darr"),
                TIMEOUT to encoded(intArrayOf(3), "iarr"),
            ),
        )
        val untouched = DebugInputRegistry.resolveComposite(TIMEOUT, timeoutDefault, "iarr")

        DebugInputRegistry.setValue(SPEED, doubleArrayOf(7.0), "darr")

        val afterUnrelatedEdit = DebugInputRegistry.resolveComposite(TIMEOUT, timeoutDefault, "iarr")
        assertNotSame(untouched, afterUnrelatedEdit)
        assertTrue(intArrayOf(3) contentEquals afterUnrelatedEdit as IntArray, "same value though")
        assertTrue(doubleArrayOf(7.0) contentEquals weights(speedDefault) as DoubleArray)
    }

    @Test
    fun `each array input gets its own cached instance`() {
        val speedDefault = doubleArrayOf(1.0)
        val timeoutDefault = doubleArrayOf(1.0)
        store = installFakeOverrideStore(
            mapOf(
                SPEED to encoded(doubleArrayOf(3.0), "darr"),
                TIMEOUT to encoded(doubleArrayOf(3.0), "darr"),
            ),
        )

        val speed = weights(speedDefault)
        val timeout = DebugInputRegistry.resolveComposite(TIMEOUT, timeoutDefault, "darr")

        assertNotSame(speed, timeout, "two ids must not share one decoded instance")
        assertSame(speed, weights(speedDefault))
        assertSame(timeout, DebugInputRegistry.resolveComposite(TIMEOUT, timeoutDefault, "darr"))
    }

    @Test
    fun `an object array override is cached the same way`() {
        val default = arrayOf("localhost")
        store = installFakeOverrideStore(mapOf(SPEED to encoded(arrayOf("api"), "arr<str>")))

        val first = DebugInputRegistry.resolveComposite(SPEED, default, "arr<str>")
        assertSame(first, DebugInputRegistry.resolveComposite(SPEED, default, "arr<str>"))

        DebugInputRegistry.clearOverride(SPEED)
        assertSame(default, DebugInputRegistry.resolveComposite(SPEED, default, "arr<str>"))
    }

    @Test
    fun `a List override resolves to an equal value on every read`() {
        // Only arrays are cached. A read-only List has no identity a mutation can
        // expose, so a fresh instance per read is indistinguishable from a cached one.
        val default = listOf("localhost")
        store = installFakeOverrideStore(mapOf(SPEED to encoded(listOf("api"), "lst<str>")))

        val first = DebugInputRegistry.resolveComposite(SPEED, default, "lst<str>")
        val second = DebugInputRegistry.resolveComposite(SPEED, default, "lst<str>")

        assertEquals(first, second)
    }

    @Test
    fun `an array override is ignored in a release build and the default instance stands`() {
        val default = doubleArrayOf(1.0, 2.0)
        store = installFakeOverrideStore(mapOf(SPEED to encoded(doubleArrayOf(3.0), "darr")))
        installIsDebugBuildForTesting(false)

        assertSame(default, weights(default))
    }
}
