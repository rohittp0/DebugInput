package com.rohittp.debuginput.sample.domain

import android.content.pm.ApplicationInfo
import com.rohittp.debuginput.DebugInputInitializer
import com.rohittp.debuginput.DebugInputInternalApi
import com.rohittp.debuginput.DebugInputRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The whole stack on Android, with nothing faked: the IR-rewritten getter, the registry,
 * the real SharedPreferences override store, and a `Context` captured the way a real
 * process captures it — by the `ContentProvider` running.
 *
 * Nothing here reaches into the runtime's internals; this is the API a consumer has.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned one below compileSdk, which is newer than this Robolectric knows about.
@Config(sdk = [36])
@OptIn(DebugInputInternalApi::class)
class TransformEndToEndTest {

    @Before
    fun startTheProcess() {
        // Exactly what Android does at process start.
        Robolectric.setupContentProvider(DebugInputInitializer::class.java)
        // Robolectric's application is not debuggable by default, and a non-debuggable
        // process is inert by design.
        val applicationInfo = RuntimeEnvironment.getApplication().applicationInfo
        applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE

        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    @After
    fun tidyUp() {
        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    @Test
    fun `a property reads its default when nothing is overridden`() {
        assertEquals(10, speed)
    }

    @Test
    fun `an override changes what an internal top-level property returns`() {
        DebugInputRegistry.setInt("com.rohittp.debuginput.sample.domain.speed", 25)

        assertEquals(25, speed)
    }

    /**
     * The read is inside the declaring file — the case that compiled to `getstatic` and
     * bypassed the registry entirely until the getter's origin was reset to DEFINED. For
     * a private input this is the only place a read can happen, so without this test the
     * regression would be invisible.
     */
    @Test
    fun `an override changes what a private top-level property returns`() {
        DebugInputRegistry.setInt(
            "com.rohittp.debuginput.sample.domain.Physics.kt.droppedFrameBudget",
            9,
        )

        assertEquals(9, animationBudget())
    }

    @Test
    fun `an override changes what an object member returns`() {
        DebugInputRegistry.setInt("com.rohittp.debuginput.sample.domain.Tiers.freeLimit", 99)

        assertEquals(99, Tiers.freeLimit)
    }

    /** `freeLimit`'s default is `baseLimit * 5`, which the compiler cannot fold. */
    @Test
    fun `a non-constant default survives the transform`() {
        assertEquals(25, Tiers.freeLimit)
    }

    @Test
    fun `an override survives a relaunch`() {
        DebugInputRegistry.setInt("com.rohittp.debuginput.sample.domain.speed", 25)

        DebugInputRegistry.resetForTesting()

        assertEquals(25, speed)
    }

    @Test
    fun `a process that is not debuggable ignores every override`() {
        DebugInputRegistry.setInt("com.rohittp.debuginput.sample.domain.speed", 25)
        assertEquals(25, speed)

        val applicationInfo = RuntimeEnvironment.getApplication().applicationInfo
        applicationInfo.flags = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        DebugInputRegistry.resetForTesting()

        assertEquals(10, speed)
    }
}

/**
 * The whole supported type table on Android, through the real SharedPreferences store and a
 * `Context` captured the way a real process captures it. Shares its case list with the iOS
 * suite via `commonTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(DebugInputInternalApi::class)
class TypeSweepTest {

    @Before
    fun startTheProcess() {
        Robolectric.setupContentProvider(DebugInputInitializer::class.java)
        val applicationInfo = RuntimeEnvironment.getApplication().applicationInfo
        applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    @After
    fun tidyUp() {
        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    @Test
    fun `every supported type resolves and overrides and persists and clears`() {
        assertEverySupportedTypeOverrides()
    }

    @Test
    fun `a composite override of the wrong shape resolves its default`() {
        assertDormantCompositeResolvesItsDefault()
    }
}
