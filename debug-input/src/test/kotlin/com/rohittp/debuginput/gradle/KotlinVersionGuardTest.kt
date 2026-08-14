package com.rohittp.debuginput.gradle

import org.junit.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinVersionGuardTest {

    @Test
    fun `the supported minor passes`() {
        assertNull(kotlinVersionMismatchMessage("1.0.0", "2.3.21"))
        assertNull(kotlinVersionMismatchMessage("1.0.0", "2.3.0"))
        assertNull(kotlinVersionMismatchMessage("1.0.0", "2.3.30-Beta2"))
    }

    @Test
    fun `another minor fails`() {
        listOf("2.2.20", "2.4.0", "3.0.0", "1.9.24").forEach { version ->
            assertTrue(
                kotlinVersionMismatchMessage("1.0.0", version) != null,
                "Kotlin $version should be rejected",
            )
        }
    }

    @Test
    fun `an unreadable version fails rather than being assumed compatible`() {
        listOf("", "unknown", "2", "x.y.z").forEach { version ->
            assertTrue(
                kotlinVersionMismatchMessage("1.0.0", version) != null,
                "'$version' should be rejected",
            )
        }
    }

    @Test
    fun `the message names both versions and what to do`() {
        val message = kotlinVersionMismatchMessage("1.0.0", "2.4.0")

        // The whole point of the guard is that the alternative is a NoSuchMethodError
        // from inside FIR, so the message has to stand on its own.
        assertTrue(message != null)
        assertTrue("2.3" in message, message)
        assertTrue("2.4.0" in message, message)
        assertTrue("debug-input 1.0.0" in message, message)
    }
}
