package com.rohittp.debuginput

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a live read subscribes to. */
@OptIn(DebugInputInternalApi::class)
class ListenerTest {

    private lateinit var store: FakeOverrideStore

    @BeforeTest
    fun installOverrideStore() {
        store = installFakeOverrideStore()
    }

    @AfterTest
    fun restoreOverrideStore() {
        restorePlatformOverrideStore()
    }

    private fun record(id: String): MutableList<Any?> {
        val seen = mutableListOf<Any?>()
        DebugInputRegistry.addListener(id) { seen += it }
        return seen
    }

    @Test
    fun `a listener fires with the new override on setInt`() {
        val seen = record(SPEED)

        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(listOf<Any?>(25), seen)
    }

    @Test
    fun `a listener fires on every setInt in order`() {
        val seen = record(SPEED)

        DebugInputRegistry.setInt(SPEED, 25)
        DebugInputRegistry.setInt(SPEED, 40)

        assertEquals(listOf<Any?>(25, 40), seen)
    }

    @Test
    fun `a listener fires with null on clearOverride meaning back to the default`() {
        DebugInputRegistry.setInt(SPEED, 25)
        val seen = record(SPEED)

        DebugInputRegistry.clearOverride(SPEED)

        assertEquals(listOf<Any?>(null), seen)
    }

    @Test
    fun `every registered listener fires with null on clearAll`() {
        DebugInputRegistry.setInt(SPEED, 25)
        val speedSeen = record(SPEED)
        val timeoutSeen = record(TIMEOUT)

        DebugInputRegistry.clearAll()

        assertEquals(listOf<Any?>(null), speedSeen)
        assertEquals(listOf<Any?>(null), timeoutSeen, "an id at its default is told too")
    }

    @Test
    fun `the function addListener returns removes that listener`() {
        val seen = mutableListOf<Any?>()
        val remove = DebugInputRegistry.addListener(SPEED) { seen += it }

        remove()
        DebugInputRegistry.setInt(SPEED, 25)
        DebugInputRegistry.clearOverride(SPEED)
        DebugInputRegistry.clearAll()

        assertTrue(seen.isEmpty(), "removed listener still heard $seen")
    }

    @Test
    fun `removing one listener leaves the others registered for that id`() {
        val removedSeen = mutableListOf<Any?>()
        val remove = DebugInputRegistry.addListener(SPEED) { removedSeen += it }
        val keptSeen = record(SPEED)

        remove()
        DebugInputRegistry.setInt(SPEED, 25)

        assertTrue(removedSeen.isEmpty(), "removed listener still heard $removedSeen")
        assertEquals(listOf<Any?>(25), keptSeen)
    }

    @Test
    fun `two listeners registered for one id both fire`() {
        val first = record(SPEED)
        val second = record(SPEED)

        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(listOf<Any?>(25), first)
        assertEquals(listOf<Any?>(25), second)
    }

    @Test
    fun `the same listener registered twice for one id is removed one registration at a time`() {
        val seen = mutableListOf<Any?>()
        val listener: (Any?) -> Unit = { seen += it }
        val removeFirst = DebugInputRegistry.addListener(SPEED, listener)
        DebugInputRegistry.addListener(SPEED, listener)

        removeFirst()
        DebugInputRegistry.setInt(SPEED, 25)

        assertEquals(listOf<Any?>(25), seen, "one registration remains")
    }

    @Test
    fun `a listener registered for another id does not fire`() {
        val timeoutSeen = record(TIMEOUT)

        DebugInputRegistry.setInt(SPEED, 25)
        DebugInputRegistry.clearOverride(SPEED)

        assertTrue(timeoutSeen.isEmpty(), "listener for another id heard $timeoutSeen")
    }

    @Test
    fun `removing the last listener for an id does not disturb the other ids`() {
        val remove = DebugInputRegistry.addListener(SPEED) { }
        val timeoutSeen = record(TIMEOUT)

        remove()
        DebugInputRegistry.setInt(TIMEOUT, 90)

        assertEquals(listOf<Any?>(90), timeoutSeen)
    }

    @Test
    fun `removing a listener twice is harmless`() {
        val seen = mutableListOf<Any?>()
        val remove = DebugInputRegistry.addListener(SPEED) { seen += it }
        val keptSeen = record(SPEED)

        remove()
        remove()
        DebugInputRegistry.setInt(SPEED, 25)

        assertTrue(seen.isEmpty())
        assertEquals(listOf<Any?>(25), keptSeen)
    }
}
