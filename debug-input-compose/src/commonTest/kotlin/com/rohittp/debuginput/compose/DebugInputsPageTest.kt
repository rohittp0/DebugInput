@file:OptIn(ExperimentalTestApi::class)

package com.rohittp.debuginput.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.rohittp.debuginput.DebugInputDescriptor
import com.rohittp.debuginput.DebugInputRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val SPEED_ID = "com.app.physics.speed"

class DebugInputsPageTest {

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

    @Test
    fun anEmptyDescriptorListRendersNothing() = runComposeUiTest {
        setContent { DebugInputsPage() }

        onRoot().onChildren().assertCountEquals(0)
        onNodeWithText("Debug inputs").assertDoesNotExist()
        onNodeWithText("Reset all").assertDoesNotExist()
    }

    @Test
    fun aRowShowsItsDefault() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(intInput())) }

        onNodeWithText("speed").assertExists()
        onNodeWithText("10").assertExists()
        onNodeWithText("changed").assertDoesNotExist()
    }

    @Test
    fun typingAValuePersistsItAndShowsTheChangedIndicator() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(intInput())) }

        onNodeWithText("10").performTextReplacement("42")

        onNodeWithText("42").assertExists()
        onNodeWithText("changed").assertExists()
        assertEquals(42, DebugInputRegistry.overrideOf(SPEED_ID))
    }

    @Test
    fun anOverrideEqualToTheDefaultIsNotFlaggedAsChanged() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(intInput())) }

        onNodeWithText("10").performTextReplacement("10")

        onNodeWithText("changed").assertDoesNotExist()
    }

    @Test
    fun resetRestoresTheDefaultAndClearsTheIndicator() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(intInput())) }
        onNodeWithText("10").performTextReplacement("42")

        onNodeWithText("Reset").performClick()

        onNodeWithText("10").assertExists()
        onNodeWithText("changed").assertDoesNotExist()
        assertNull(DebugInputRegistry.overrideOf(SPEED_ID))
    }

    @Test
    fun resetAllRestoresEveryDefault() = runComposeUiTest {
        val other = intInput(id = "com.app.physics.gravity", displayName = "gravity", default = 9)
        setContent { DebugInputsPage(listOf(intInput(), other)) }
        onNodeWithText("10").performTextReplacement("42")
        onNodeWithText("9").performTextReplacement("7")

        onNodeWithText("Reset all").performClick()

        onNodeWithText("10").assertExists()
        onNodeWithText("9").assertExists()
        onAllNodesWithText("changed").assertCountEquals(0)
        assertNull(DebugInputRegistry.overrideOf(SPEED_ID))
        assertNull(DebugInputRegistry.overrideOf("com.app.physics.gravity"))
    }

    @Test
    fun textThatIsNotAWholeNumberIsKeptAndChangesNothing() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(intInput())) }

        onNodeWithText("10").performTextReplacement("12x")

        // What was typed survives, the input keeps resolving to its default, and
        // nothing threw on the way.
        onNodeWithText("12x").assertExists()
        onNodeWithText("must be a whole number; still 10").assertExists()
        assertNull(DebugInputRegistry.overrideOf(SPEED_ID))
    }

    @Test
    fun anEmptyFieldIsKeptAndChangesNothing() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(intInput())) }

        onNodeWithText("10").performTextReplacement("")

        onNodeWithText("must be a whole number; still 10").assertExists()
        assertNull(DebugInputRegistry.overrideOf(SPEED_ID))
    }

    @Test
    fun anUnsupportedTypeKeyShowsTheNoRendererRow() = runComposeUiTest {
        val flag = intInput(
            id = "com.app.physics.enabled",
            displayName = "enabled",
            typeKey = "kotlin.Boolean",
            default = true,
        )
        setContent { DebugInputsPage(listOf(flag)) }

        onNodeWithText("enabled").assertExists()
        onNodeWithText("no renderer registered for kotlin.Boolean").assertExists()
    }

    @Test
    fun docsAreRevealedByTheInfoIcon() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(intInput(docs = "Player speed in m/s"))) }
        onNodeWithText("Player speed in m/s").assertDoesNotExist()

        onNodeWithContentDescription("docs for speed").performClick()

        onNodeWithText("Player speed in m/s").assertExists()
    }

    @Test
    fun thereIsNoInfoIconWithoutDocs() = runComposeUiTest {
        setContent { DebugInputsPage(listOf(intInput())) }

        onNodeWithContentDescription("docs for speed").assertDoesNotExist()
    }

    @Test
    fun descriptorsAreDedupedById() = runComposeUiTest {
        // A diamond in the consumer's project graph hands the page the same input once
        // per path to the module that declares it.
        setContent { DebugInputsPage(listOf(intInput(), intInput(), intInput())) }

        onAllNodesWithText("speed").assertCountEquals(1)
    }

    @Test
    fun theModuleLevelIsAbsentWithOneModule() = runComposeUiTest {
        val inputs = listOf(
            intInput(),
            intInput(id = "com.app.limits.cap", displayName = "cap", section = "Limits"),
        )
        setContent { DebugInputsPage(inputs) }

        onNodeWithText("Physics").assertExists()
        onNodeWithText("Limits").assertExists()
        onNodeWithText(":domain").assertDoesNotExist()
    }

    @Test
    fun theModuleLevelIsPresentWithTwoModules() = runComposeUiTest {
        val inputs = listOf(
            intInput(),
            intInput(
                id = "com.app.limits.cap",
                displayName = "cap",
                module = ":app",
                section = "Limits",
            ),
        )
        setContent { DebugInputsPage(inputs) }

        onNodeWithText(":domain").assertExists()
        onNodeWithText(":app").assertExists()
        onNodeWithText("Physics").assertExists()
        onNodeWithText("Limits").assertExists()
    }
}

private fun intInput(
    id: String = SPEED_ID,
    displayName: String = "speed",
    module: String = ":domain",
    section: String = "Physics",
    typeKey: String = "kotlin.Int",
    docs: String = "",
    default: Any? = 10,
) = DebugInputDescriptor(
    id = id,
    displayName = displayName,
    module = module,
    section = section,
    typeKey = typeKey,
    docs = docs,
    default = default,
)
