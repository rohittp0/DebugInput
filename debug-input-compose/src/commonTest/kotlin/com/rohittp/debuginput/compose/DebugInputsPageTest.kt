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
    fun compilerSectionsOpenAsDedicatedPagesWithTheirDescription() = runComposeUiTest {
        // A top-level property in MagicNumbers.kt has the same display section as the enum.
        // The enum FQN must keep those two sections distinct.
        val ordinaryInput = intInput(section = "MagicNumbers")
        val enumInput = intInput(
            id = "com.app.magic.MagicNumbers.INTRO.values",
            displayName = "INTRO",
            section = "MagicNumbers",
            sectionDescription = "Numbers that shape animation and rendering.",
            sectionPageId = "enum:com.app.magic.MagicNumbers",
        )
        setContent { DebugInputsPage(listOf(ordinaryInput, enumInput)) }

        onNodeWithContentDescription("open MagicNumbers").assertExists()
        onNodeWithText("speed").assertExists()
        onNodeWithText("INTRO").assertDoesNotExist()
        onNodeWithText("Numbers that shape animation and rendering.").assertDoesNotExist()

        onNodeWithContentDescription("open MagicNumbers").performClick()

        onNodeWithText("INTRO").assertExists()
        onNodeWithText("Numbers that shape animation and rendering.").assertExists()
        onNodeWithText("speed").assertDoesNotExist()

        onNodeWithContentDescription("back to debug inputs").performClick()

        onNodeWithText("speed").assertExists()
        onNodeWithText("INTRO").assertDoesNotExist()
    }

    @Test
    fun namedSectionsGroupInputsOnOnePage() = runComposeUiTest {
        val model = intInput(
            id = "com.app.ai.model",
            displayName = "model",
            section = "Assistant",
            sectionPageId = "custom:Assistant",
        )
        val limit = intInput(
            id = "com.app.ai.tokenLimit",
            displayName = "tokenLimit",
            section = "Assistant",
            sectionPageId = "custom:Assistant",
        )
        setContent { DebugInputsPage(listOf(model, limit)) }

        onNodeWithContentDescription("open Assistant").assertExists()
        onNodeWithText("2 inputs").assertExists()
        onNodeWithText("model").assertDoesNotExist()
        onNodeWithText("tokenLimit").assertDoesNotExist()

        onNodeWithContentDescription("open Assistant").performClick()

        onNodeWithText("model").assertExists()
        onNodeWithText("tokenLimit").assertExists()
    }

    @Test
    fun copyJsonReportsHowManyChangesWereCopied() = runComposeUiTest {
        val input = intInput()
        DebugInputRegistry.setValue(input.id, 42, "int")
        setContent { DebugInputsPage(listOf(input)) }

        onNodeWithText("Copy JSON (1)").performClick()

        onNodeWithText("Copied 1 change").assertExists()
    }

    @Test
    fun changedJsonIsStableEscapedAndContainsSourceContext() {
        val model = intInput(
            id = "com.app.ai.model",
            displayName = "model",
            module = ":app",
            section = "Assistant",
            typeKey = "kotlin.String",
            default = "old",
            spec = "str",
        )
        val speed = intInput()
        val unchanged = intInput(
            id = "com.app.physics.unchanged",
            displayName = "unchanged",
            default = 7,
        )
        DebugInputRegistry.setValue(model.id, "line \"one\"\nline two", "str")
        DebugInputRegistry.setValue(speed.id, 42, "int")
        DebugInputRegistry.setValue(unchanged.id, 7, "int")

        assertEquals(
            """
            {
              "version": 1,
              "changes": [
                {
                  "id": "com.app.ai.model",
                  "module": ":app",
                  "section": "Assistant",
                  "name": "model",
                  "type": "kotlin.String",
                  "default": "old",
                  "value": "line \"one\"\nline two"
                },
                {
                  "id": "com.app.physics.speed",
                  "module": ":domain",
                  "section": "Physics",
                  "name": "speed",
                  "type": "kotlin.Int",
                  "default": 10,
                  "value": 42
                }
              ]
            }
            """.trimIndent(),
            changedValuesJson(changedInputs(listOf(speed, model, unchanged, speed))),
        )
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
    sectionDescription: String = "",
    sectionPageId: String? = null,
    spec: String = "",
) = DebugInputDescriptor(
    id = id,
    displayName = displayName,
    module = module,
    section = section,
    typeKey = typeKey,
    docs = docs,
    default = default,
    sectionDescription = sectionDescription,
    sectionPageId = sectionPageId,
    spec = spec,
)
