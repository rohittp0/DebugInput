package com.rohittp.debuginput

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The real SharedPreferences-backed override store, in its own preferences file.
 * Every test starts from an empty file and no captured `Context`.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned: the module compiles against 37, one past the newest SDK this
// Robolectric knows. Nothing here is SDK-sensitive.
@Config(sdk = [36])
@OptIn(DebugInputInternalApi::class)
class SharedPreferencesOverrideStoreTest {

    private val preferences: SharedPreferences
        get() = RuntimeEnvironment.getApplication()
            .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    @Before
    fun captureContext() {
        preferences.edit().clear().commit()
        AndroidContextHolder.applicationContext = RuntimeEnvironment.getApplication()
        // Robolectric's application is not debuggable, and these tests are about the
        // store rather than about build types. The debuggable flag itself is asserted
        // separately below.
        installIsDebugBuildForTesting(true)
        DebugInputRegistry.resetForTesting()
    }

    @After
    fun releaseContext() {
        preferences.edit().clear().commit()
        AndroidContextHolder.applicationContext = null
        installIsDebugBuildForTesting(null)
        DebugInputRegistry.resetForTesting()
    }

    @Test
    fun `a debuggable process is a debug build`() {
        installIsDebugBuildForTesting(null)
        val applicationInfo = RuntimeEnvironment.getApplication().applicationInfo
        applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE

        assertTrue(platformIsDebugBuild)
    }

    @Test
    fun `a process without the debuggable flag is not a debug build`() {
        installIsDebugBuildForTesting(null)
        val applicationInfo = RuntimeEnvironment.getApplication().applicationInfo
        applicationInfo.flags = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()

        assertFalse(platformIsDebugBuild)
    }

    /**
     * An input read from a static initialiser can run before the `ContentProvider` has
     * captured a `Context`, leaving the build type unknowable. An unprovable build is
     * treated as release.
     */
    @Test
    fun `a process with no captured context is not a debug build`() {
        installIsDebugBuildForTesting(null)
        AndroidContextHolder.applicationContext = null

        assertFalse(platformIsDebugBuild)
    }

    @Test
    fun `setInt writes the encoded override to the SharedPreferences override store`() {
        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(encodeInt(25), preferences.getString(SPEED, null))
    }

    @Test
    fun `an override persists across launches`() {
        DebugInputRegistry.setInt(SPEED, 25)

        // The process restarts: a new registry, the same preferences file.
        DebugInputRegistry.resetForTesting()

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    @Test
    fun `an override already in the preferences file resolves on the first read`() {
        preferences.edit().putString(SPEED, encodeInt(25)).commit()
        DebugInputRegistry.resetForTesting()

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(10, DebugInputRegistry.resolveInt(TIMEOUT, 10))
    }

    @Test
    fun `clearOverride removes just that id from the preferences file`() {
        DebugInputRegistry.setInt(SPEED, 25)
        DebugInputRegistry.setInt(TIMEOUT, 90)

        DebugInputRegistry.clearOverride(SPEED)
        DebugInputRegistry.resetForTesting()

        assertNull(preferences.getString(SPEED, null))
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(90, DebugInputRegistry.resolveInt(TIMEOUT, 30))
    }

    @Test
    fun `clearAll empties the preferences file and restores every default`() {
        DebugInputRegistry.setInt(SPEED, 25)
        DebugInputRegistry.setInt(TIMEOUT, 90)

        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()

        assertTrue(preferences.all.isEmpty(), "preferences file still holds ${preferences.all}")
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals(30, DebugInputRegistry.resolveInt(TIMEOUT, 30))
    }

    @Test
    fun `a dormant override of another type in the preferences file is ignored and kept`() {
        preferences.edit().putString(SPEED, "bln:4:true").commit()
        DebugInputRegistry.resetForTesting()

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
        assertEquals("bln:4:true", preferences.getString(SPEED, null), "dormant, not deleted")
    }

    @Test
    fun `a non-string entry in the preferences file is skipped instead of crashing a read`() {
        preferences.edit()
            .putInt("com.app.written.by.something.else", 5)
            .putString(SPEED, encodeInt(25))
            .commit()
        DebugInputRegistry.resetForTesting()

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    @Test
    fun `reads resolve defaults before the ContentProvider has captured a Context`() {
        preferences.edit().putString(SPEED, encodeInt(25)).commit()
        AndroidContextHolder.applicationContext = null
        DebugInputRegistry.resetForTesting()

        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    @Test
    fun `a later read hydrates once the ContentProvider has captured a Context`() {
        preferences.edit().putString(SPEED, encodeInt(25)).commit()
        AndroidContextHolder.applicationContext = null
        DebugInputRegistry.resetForTesting()
        assertEquals(10, DebugInputRegistry.resolveInt(SPEED, 10))

        AndroidContextHolder.applicationContext = RuntimeEnvironment.getApplication()

        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    @Test
    fun `a composite override survives the SharedPreferences override store`() {
        val hosts = listOf("api.example.com", "a:b:c", "", "emoji \uD83D\uDE00")

        DebugInputRegistry.setValue(SPEED, hosts, "lst<str>")
        DebugInputRegistry.resetForTesting()

        assertEquals(
            hosts,
            DebugInputRegistry.resolveComposite(SPEED, emptyList<String>(), "lst<str>"),
        )
    }

    @Test
    fun `an array override survives the SharedPreferences override store`() {
        val weights = doubleArrayOf(1.5, -0.0, Double.NaN, Double.NEGATIVE_INFINITY)

        DebugInputRegistry.setValue(SPEED, weights, "darr")
        DebugInputRegistry.resetForTesting()

        val resolved = DebugInputRegistry.resolveComposite(SPEED, doubleArrayOf(), "darr")
        assertTrue(sameValue(weights, resolved), "resolved $resolved")
    }

    @Test
    fun `an enum override survives the SharedPreferences override store`() {
        DebugInputRegistry.setValue(SPEED, Tier.TEAM, "enm")
        DebugInputRegistry.resetForTesting()

        assertEquals(Tier.TEAM, DebugInputRegistry.resolveEnum(SPEED, Tier.FREE, tierEntries))
        assertEquals("enm:4:TEAM", preferences.getString(SPEED, null))
    }

    @Test
    fun `DebugInputInitializer captures the application Context and hydrates nothing`() {
        preferences.edit().putString(SPEED, encodeInt(25)).commit()
        AndroidContextHolder.applicationContext = null
        DebugInputRegistry.resetForTesting()

        Robolectric.setupContentProvider(DebugInputInitializer::class.java)

        assertSame(RuntimeEnvironment.getApplication(), AndroidContextHolder.applicationContext)
        // The provider itself does no work: the value only arrives on the first read.
        assertEquals(25, DebugInputRegistry.resolveInt(SPEED, 10))
    }

    private companion object {
        const val PREFERENCES_FILE = "debug_input_overrides"
    }
}
