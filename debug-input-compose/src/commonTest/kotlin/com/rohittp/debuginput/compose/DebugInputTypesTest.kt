@file:OptIn(ExperimentalTestApi::class)

package com.rohittp.debuginput.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.rohittp.debuginput.DebugInputDescriptor
import com.rohittp.debuginput.DebugInputRegistry
import com.rohittp.debuginput.TAG_BOOLEAN
import com.rohittp.debuginput.TAG_CHAR
import com.rohittp.debuginput.TAG_DOUBLE
import com.rohittp.debuginput.TAG_ENUM
import com.rohittp.debuginput.TAG_FLOAT
import com.rohittp.debuginput.TAG_INT
import com.rohittp.debuginput.TAG_LONG
import com.rohittp.debuginput.TAG_SHORT
import com.rohittp.debuginput.TAG_STRING
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ID = "com.app.types.value"

private enum class Tier { FREE, PRO }

/** One input of every supported shape, edited through the page. */
class DebugInputTypesTest {

    @BeforeTest
    fun clearOverrides() {
        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    @AfterTest
    fun clearOverridesAgain() {
        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    // ---- Scalars ----

    @Test
    fun aBooleanRendersASwitch() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = TAG_BOOLEAN, default = false))) }

        onNodeWithContentDescription("value").performClick()

        assertEquals(true, DebugInputRegistry.overrideOf(ID, TAG_BOOLEAN))
        onNodeWithText("changed").assertExists()
    }

    @Test
    fun anEnumRendersADropdownOfItsConstants() = runComposeUiTest {
        val descriptor = input(
            spec = TAG_ENUM,
            typeKey = "com.app.Tier",
            default = Tier.FREE,
            constants = listOf("FREE", "PRO"),
        )
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithContentDescription("choose value").performClick()
        onNodeWithText("PRO").performClick()

        // The wire form holds the constant's name; the registry maps it back on read.
        assertEquals("PRO", DebugInputRegistry.overrideOf(ID, TAG_ENUM))
        onNodeWithText("changed").assertExists()
    }

    @Test
    fun anEnumWithNoConstantsFallsBackToTheNoRendererRow() = runComposeUiTest {
        val descriptor = input(spec = TAG_ENUM, typeKey = "com.app.Tier", default = Tier.FREE)
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText("no renderer registered for com.app.Tier").assertExists()
    }

    @Test
    fun aCharTakesOneUnitAndRefusesTwo() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = TAG_CHAR, default = 'a'))) }

        onNodeWithText("a").performTextReplacement("bc")

        onNodeWithText("must be exactly one character; still a").assertExists()
        assertNull(DebugInputRegistry.overrideOf(ID, TAG_CHAR))

        onNodeWithText("bc").performTextReplacement("b")

        assertEquals('b', DebugInputRegistry.overrideOf(ID, TAG_CHAR))
    }

    @Test
    fun aShortRefusesAValueOutsideItsRange() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = TAG_SHORT, default = 5.toShort()))) }

        onNodeWithText("5").performTextReplacement("40000")

        onNodeWithText("must be a whole number in -32768..32767; still 5").assertExists()
        assertNull(DebugInputRegistry.overrideOf(ID, TAG_SHORT))

        onNodeWithText("40000").performTextReplacement("-32768")

        assertEquals((-32768).toShort(), DebugInputRegistry.overrideOf(ID, TAG_SHORT))
    }

    @Test
    fun aLongTakesItsWholeRange() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = TAG_LONG, default = 1L))) }

        onNodeWithText("1").performTextReplacement("9223372036854775807")

        assertEquals(Long.MAX_VALUE, DebugInputRegistry.overrideOf(ID, TAG_LONG))
    }

    @Test
    fun aDoubleRefusesALiteralThatOverflowsToInfinity() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = TAG_DOUBLE, default = 1.5))) }

        onNodeWithText("1.5").performTextReplacement("1e999")

        onNodeWithText("must be a finite number, NaN, Infinity or -Infinity; still 1.5")
            .assertExists()
        assertNull(DebugInputRegistry.overrideOf(ID, TAG_DOUBLE))

        onNodeWithText("1e999").performTextReplacement("NaN")

        assertTrue((DebugInputRegistry.overrideOf(ID, TAG_DOUBLE) as Double).isNaN())
    }

    @Test
    fun aFloatTakesTheSpecialsTheCodecWrites() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = TAG_FLOAT, default = 1.5f))) }

        onNodeWithText("1.5").performTextReplacement("Infinity")

        assertEquals(Float.POSITIVE_INFINITY, DebugInputRegistry.overrideOf(ID, TAG_FLOAT))
    }

    @Test
    fun aStringKeepsACharacterTheWireFormWouldOtherwiseNeedEscaped() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = TAG_STRING, default = "hi"))) }

        onNodeWithText("hi").performTextReplacement("a:b")

        assertEquals("a:b", DebugInputRegistry.overrideOf(ID, TAG_STRING))
    }

    @Test
    fun theSignButtonMakesANegativeValueTypable() = runComposeUiTest {
        // iOS number pads have no minus key, so this is the only way in for a negative.
        setContent { DebugInputsPage(listOf(input(spec = TAG_INT, default = 10))) }

        onNodeWithContentDescription("negate value").performClick()

        assertEquals(-10, DebugInputRegistry.overrideOf(ID, TAG_INT))
    }

    // ---- Composites ----

    @Test
    fun aListAddsAndRemovesElements() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = "lst<int>", default = listOf(1)))) }

        onNodeWithText("Add").performClick()

        assertEquals(listOf(1, 0), DebugInputRegistry.overrideOf(ID, "lst<int>"))

        onNodeWithContentDescription("remove value 1").performClick()

        assertEquals(listOf(0), DebugInputRegistry.overrideOf(ID, "lst<int>"))
    }

    @Test
    fun anEmptyContainerIsRepresentableAndEditable() = runComposeUiTest {
        val descriptor = input(spec = "lst<str>", default = emptyList<String>())
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText("empty").assertExists()
        onNodeWithText("Add").performClick()

        assertEquals(listOf(""), DebugInputRegistry.overrideOf(ID, "lst<str>"))

        onNodeWithContentDescription("remove value 1").performClick()

        assertEquals(emptyList<String>(), DebugInputRegistry.overrideOf(ID, "lst<str>"))
        onNodeWithText("empty").assertExists()
    }

    @Test
    fun aSetRefusesADuplicateElement() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = "set<int>", default = setOf(1, 2)))) }

        onAllNodes(hasSetTextAction())[1].performTextReplacement("1")

        onNodeWithText("already in the set").assertExists()
        assertNull(DebugInputRegistry.overrideOf(ID, "set<int>"))

        onAllNodes(hasSetTextAction())[1].performTextReplacement("3")

        assertEquals(setOf(1, 3), DebugInputRegistry.overrideOf(ID, "set<int>"))
    }

    @Test
    fun addingToASetSeedsAValueItDoesNotAlreadyHold() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = "set<int>", default = setOf(0)))) }

        onNodeWithText("Add").performClick()

        assertEquals(setOf(0, 1), DebugInputRegistry.overrideOf(ID, "set<int>"))
    }

    @Test
    fun anIntArrayIsWrittenAsANewInstance() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = "iarr", default = intArrayOf(1, 2)))) }

        onAllNodes(hasSetTextAction())[0].performTextReplacement("5")

        val stored = DebugInputRegistry.overrideOf(ID, "iarr")
        assertTrue(stored is IntArray)
        assertContentEquals(intArrayOf(5, 2), stored)
        onNodeWithText("changed").assertExists()
    }

    @Test
    fun anArrayOverrideMatchingTheDefaultIsNotFlaggedAsChanged() = runComposeUiTest {
        // Arrays compare by identity, so the indicator has to compare contents.
        setContent { DebugInputsPage(listOf(input(spec = "iarr", default = intArrayOf(1, 2)))) }

        onAllNodes(hasSetTextAction())[0].performTextReplacement("5")
        onNodeWithText("changed").assertExists()

        onAllNodes(hasSetTextAction())[0].performTextReplacement("1")

        onNodeWithText("changed").assertDoesNotExist()
        assertNotNull(DebugInputRegistry.overrideOf(ID, "iarr"))
    }

    @Test
    fun aDoubleArrayAddsAnElementOfItsOwnElementType() = runComposeUiTest {
        // The eight primitive arrays differ only in one mapping each, so a second tag
        // catches a transposition the IntArray test cannot.
        setContent { DebugInputsPage(listOf(input(spec = "darr", default = doubleArrayOf(1.0)))) }

        onNodeWithText("Add").performClick()

        val stored = DebugInputRegistry.overrideOf(ID, "darr")
        assertTrue(stored is DoubleArray)
        assertContentEquals(doubleArrayOf(1.0, 0.0), stored)
    }

    @Test
    fun anObjectArrayEditsLikeAList() = runComposeUiTest {
        val descriptor = input(spec = "arr<str>", default = arrayOf("a", "b"))
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText("b").performTextReplacement("c")

        val stored = DebugInputRegistry.overrideOf(ID, "arr<str>")
        assertTrue(stored is Array<*>)
        assertEquals(listOf("a", "c"), stored.toList())
    }

    @Test
    fun aPairEditsBothHalvesAndCannotChangeArity() = runComposeUiTest {
        val descriptor = input(spec = "pair<int,str>", default = Pair(3, "backoff"))
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText("first").assertExists()
        onNodeWithText("second").assertExists()
        onNodeWithText("Add").assertDoesNotExist()
        onNodeWithContentDescription("remove value 1").assertDoesNotExist()

        onNodeWithText("backoff").performTextReplacement("linear")

        assertEquals(Pair(3, "linear"), DebugInputRegistry.overrideOf(ID, "pair<int,str>"))
    }

    @Test
    fun aTripleLabelsThreeEditors() = runComposeUiTest {
        val descriptor = input(spec = "trip<int,int,bln>", default = Triple(1, 2, false))
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText("third").assertExists()
        onNodeWithContentDescription("value 3").performClick()

        assertEquals(Triple(1, 2, true), DebugInputRegistry.overrideOf(ID, "trip<int,int,bln>"))
    }

    @Test
    fun aBooleanArrayIsAColumnOfSwitches() = runComposeUiTest {
        val descriptor = input(spec = "zarr", default = booleanArrayOf(false, false))
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithContentDescription("value 2").performClick()

        val toggled = DebugInputRegistry.overrideOf(ID, "zarr")
        assertTrue(toggled is BooleanArray)
        assertContentEquals(booleanArrayOf(false, true), toggled)

        onNodeWithText("Add").performClick()

        val grown = DebugInputRegistry.overrideOf(ID, "zarr")
        assertContentEquals(booleanArrayOf(false, true, false), grown as BooleanArray)
    }

    @Test
    fun resetRestoresAContainerDefault() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = "lst<int>", default = listOf(1, 2)))) }
        onAllNodes(hasSetTextAction())[0].performTextReplacement("9")

        onNodeWithText("Reset").performClick()

        assertNull(DebugInputRegistry.overrideOf(ID, "lst<int>"))
        // The buffers followed the registry back to the default rather than keeping the 9.
        onAllNodes(hasSetTextAction())[0].assertTextContains("1")
        onNodeWithText("changed").assertDoesNotExist()
    }

    @Test
    fun resetAllRestoresAContainerDefault() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(input(spec = "lst<int>", default = listOf(1, 2)))) }
        onAllNodes(hasSetTextAction())[0].performTextReplacement("9")
        onNodeWithText("changed").assertExists()

        onNodeWithText("Reset all").performClick()

        assertNull(DebugInputRegistry.overrideOf(ID, "lst<int>"))
        onAllNodes(hasSetTextAction())[0].assertTextContains("1")
        onNodeWithText("changed").assertDoesNotExist()
    }

    // ---- Specs the page refuses ----

    @Test
    fun anEnumInsideAContainerShowsTheNoRendererRow() = runComposeUiTest {
        // ADR-0008: a constant table cannot travel in a spec literal, so List<Tier> has no
        // wire form and therefore no renderer.
        val descriptor = input(
            spec = "lst<enm>",
            typeKey = "kotlin.collections.List<com.app.Tier>",
            default = listOf(Tier.FREE),
        )
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText("no renderer registered for kotlin.collections.List<com.app.Tier>")
            .assertExists()
    }

    @Test
    fun aNestedContainerShowsTheNoRendererRow() = runComposeUiTest {
        val descriptor = input(
            spec = "lst<lst<int>>",
            typeKey = "kotlin.collections.List<kotlin.collections.List<kotlin.Int>>",
            default = listOf(listOf(1)),
        )
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText(
            "no renderer registered for " +
                "kotlin.collections.List<kotlin.collections.List<kotlin.Int>>",
        ).assertExists()
    }

    @Test
    fun aPrimitiveArrayInsideAContainerShowsTheNoRendererRow() = runComposeUiTest {
        // `lst<iarr>` does not parse: a primitive array is a container that takes no
        // arguments, and the codec rejects a container-tagged argument rather than only one
        // that has arguments of its own. It has to, because the value parser refuses a
        // container frame below the top level — an override of this shape could be written
        // and then never read by anything.
        val descriptor = input(
            spec = "lst<iarr>",
            typeKey = "kotlin.collections.List<kotlin.IntArray>",
            default = listOf(intArrayOf(1)),
        )
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText("no renderer registered for kotlin.collections.List<kotlin.IntArray>")
            .assertExists()
    }

    @Test
    fun aValueThatIsNotItsSpecsShapeShowsTheNoRendererRow() = runComposeUiTest {
        val descriptor = input(spec = TAG_INT, typeKey = "kotlin.Boolean", default = true)
        setContent { DebugInputsPage(listOf(descriptor)) }

        onNodeWithText("no renderer registered for kotlin.Boolean").assertExists()
    }

    // ---- The spec-checked override lookup ----

    @Test
    fun aDormantOverrideOfAnotherTypeIsNotShownAsChanged() = runComposeUiTest {
        // This id was once a Boolean input's. The stored override is still there, still
        // readable, and every read of the Int input that claims the id now resolves its
        // default — so the row must agree and show no override.
        DebugInputRegistry.setValue(ID, true, TAG_BOOLEAN)

        setContent { DebugInputsPage(listOf(input(spec = TAG_INT, default = 10))) }

        onNodeWithText("10").assertExists()
        onNodeWithText("changed").assertDoesNotExist()
    }
}

private fun input(
    spec: String,
    default: Any?,
    typeKey: String = "kotlin.Int",
    displayName: String = "value",
    constants: List<String>? = null,
) = DebugInputDescriptor(
    id = ID,
    displayName = displayName,
    module = ":domain",
    section = "Types",
    typeKey = typeKey,
    docs = "",
    default = default,
    enumConstants = constants,
    spec = spec,
)
