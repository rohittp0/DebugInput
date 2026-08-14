package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSUserDefaults

/**
 * The real `NSUserDefaults` override store, in its own suite. Every test starts and
 * ends with an empty suite, so no run depends on the order tests happen to run in.
 */
@OptIn(DebugInputInternalApi::class)
class NSUserDefaultsOverrideStoreTest {

    private val store: OverrideStore get() = platformOverrideStore()!!

    @BeforeTest
    fun emptyTheOverrideSuite() {
        reset()
    }

    @AfterTest
    fun emptyTheOverrideSuiteAgain() {
        reset()
    }

    private fun reset() {
        // Not through clear(), which is one of the things under test.
        NSUserDefaults(suiteName = STORE_NAMESPACE).removePersistentDomainForName(STORE_NAMESPACE)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(APP_OWN_KEY)
        DebugInputRegistry.resetForTesting()
    }

    @Test
    fun `the override store is usable so reads never fall back to the default on iOS`() {
        assertTrue(platformOverrideStore() != null)
    }

    @Test
    fun `platformIsDebugBuild is true in a test binary`() {
        assertTrue(platformIsDebugBuild)
        assertTrue(DebugInputRegistry.isDebugBuild)
    }

    @Test
    fun `the override store round trips an encoded override`() {
        store.put(SPEED, encodeInt(25))

        assertEquals(mapOf(SPEED to encodeInt(25)), store.load())
    }

    @Test
    fun `the override store loads every override it holds and nothing else`() {
        // A key of the app's own, in the app's own suite. Not an override.
        NSUserDefaults.standardUserDefaults.setObject("int:2:99", forKey = APP_OWN_KEY)
        store.put(SPEED, encodeInt(25))
        store.put(TIMEOUT, encodeInt(90))

        assertEquals(mapOf(SPEED to encodeInt(25), TIMEOUT to encodeInt(90)), store.load())
    }

    @Test
    fun `remove drops one override and leaves the rest of the suite alone`() {
        store.put(SPEED, encodeInt(25))
        store.put(TIMEOUT, encodeInt(90))

        store.remove(SPEED)

        assertEquals(mapOf(TIMEOUT to encodeInt(90)), store.load())
    }

    @Test
    fun `clear empties the override suite`() {
        store.put(SPEED, encodeInt(25))
        store.put(TIMEOUT, encodeInt(90))

        store.clear()

        assertEquals(emptyMap(), store.load())
        assertNull(NSUserDefaults(suiteName = STORE_NAMESPACE).stringForKey(SPEED))
    }

    @Test
    fun `clearing every override cannot touch the app's own defaults`() {
        NSUserDefaults.standardUserDefaults.setObject("keep me", forKey = APP_OWN_KEY)
        store.put(SPEED, encodeInt(25))

        store.clear()

        assertEquals(
            "keep me",
            NSUserDefaults.standardUserDefaults.stringForKey(APP_OWN_KEY),
            "the override suite is separate from the app's own defaults",
        )
    }

    @Test
    fun `an override set through the registry persists across a fresh hydration`() {
        DebugInputRegistry.setInt(SPEED, 25)

        DebugInputRegistry.resetForTesting()

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    @Test
    fun `clearAll through the registry restores the defaults across a fresh hydration`() {
        DebugInputRegistry.setInt(SPEED, 25)
        DebugInputRegistry.setInt(TIMEOUT, 90)

        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(30, DebugInputRegistry.resolveInt(TIMEOUT, 30))
        assertEquals(emptyMap(), store.load())
    }

    @Test
    fun `a dormant override in the suite is ignored and kept`() {
        store.put(SPEED, "bln:4:true")

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals("bln:4:true", store.load()[SPEED], "dormant, not deleted")
    }

    @Test
    fun `a composite override survives the NSUserDefaults override store`() {
        val hosts = listOf("api.example.com", "a:b:c", "", "emoji \uD83D\uDE00")

        DebugInputRegistry.setValue(SPEED, hosts, "lst<str>")
        DebugInputRegistry.resetForTesting()

        assertEquals(
            hosts,
            DebugInputRegistry.resolveComposite(SPEED, emptyList<String>(), "lst<str>"),
        )
    }

    @Test
    fun `an array override survives the NSUserDefaults override store`() {
        val weights = doubleArrayOf(1.5, -0.0, Double.NaN, Double.NEGATIVE_INFINITY)

        DebugInputRegistry.setValue(SPEED, weights, "darr")
        DebugInputRegistry.resetForTesting()

        val resolved = DebugInputRegistry.resolveComposite(SPEED, doubleArrayOf(), "darr")
        assertTrue(sameValue(weights, resolved), "resolved $resolved")
    }

    @Test
    fun `an enum override survives the NSUserDefaults override store`() {
        DebugInputRegistry.setValue(SPEED, Tier.TEAM, "enm")
        DebugInputRegistry.resetForTesting()

        assertEquals(Tier.TEAM, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
        assertEquals("enm:4:TEAM", store.load()[SPEED])
    }

    private companion object {
        const val APP_OWN_KEY = "com.app.a.preference.of.its.own"
    }
}
