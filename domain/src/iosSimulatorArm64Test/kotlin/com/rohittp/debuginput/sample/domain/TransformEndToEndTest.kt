package com.rohittp.debuginput.sample.domain

import com.rohittp.debuginput.DebugInputInternalApi
import com.rohittp.debuginput.DebugInputRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole stack on iOS, with nothing faked: the IR-rewritten getter, the registry,
 * and the real `NSUserDefaults` override store.
 *
 * A test binary is a debug binary, so `Platform.isDebugBinary` is true here and reads
 * genuinely resolve through the registry.
 */
@OptIn(DebugInputInternalApi::class)
class TransformEndToEndTest {

    @BeforeTest
    fun clearOverrides() {
        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    @AfterTest
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
     * The read happens inside the declaring file, which is the case the JVM backend
     * compiled straight to a field access until the getter's origin was reset. Worth
     * asserting on Native too, since the backends decide this independently.
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

        // The process restarts: the registry forgets its hydrated map, NSUserDefaults
        // does not.
        DebugInputRegistry.resetForTesting()

        assertEquals(25, speed)
    }

    @Test
    fun `clearing every override restores every default`() {
        DebugInputRegistry.setInt("com.rohittp.debuginput.sample.domain.speed", 25)
        DebugInputRegistry.setInt("com.rohittp.debuginput.sample.domain.Tiers.freeLimit", 99)

        DebugInputRegistry.clearAll()

        assertEquals(10, speed)
        assertEquals(25, Tiers.freeLimit)
    }
}

/**
 * The whole supported type table on iOS, through the real `NSUserDefaults` store. The case
 * list lives in `commonTest` so this suite and the Android one cannot drift apart.
 */
@OptIn(DebugInputInternalApi::class)
class TypeSweepTest {

    @BeforeTest
    fun clearOverrides() {
        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    @AfterTest
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
