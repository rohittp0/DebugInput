package com.rohittp.debuginput.sample.shared

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.rohittp.debuginput.DebugInputInternalApi
import com.rohittp.debuginput.DebugInputRegistry
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The last seam in M1: `SampleDebugScreen` calls `DebugInputsPage()` with no arguments,
 * and the IR plugin fills in this module's descriptor function — which returns `:shared`'s
 * own descriptors (none) plus `:domain`'s.
 *
 * The descriptor function is deliberately unnameable from source, so rendering the page is
 * the only way to observe that aggregation happened. That is the point of the test: if the
 * call-site rewrite or the cross-module descriptor call were broken, the page would render
 * nothing at all and every assertion here would fail.
 */
@OptIn(ExperimentalTestApi::class, DebugInputInternalApi::class)
class PageAggregationTest {

    @AfterTest
    fun tidyUp() {
        DebugInputRegistry.clearAll()
        DebugInputRegistry.resetForTesting()
    }

    @Test
    fun `the page shows inputs declared in a dependency module`() = runComposeUiTest {
        setContent { SampleDebugScreen() }

        onNodeWithContentDescription("open Physics").performClick()
        onNodeWithText("speed").assertIsDisplayed()
        onNodeWithText("droppedFrameBudget").assertIsDisplayed()

        onNodeWithContentDescription("back to debug inputs").performClick()
        onNodeWithContentDescription("open Tiers").performClick()
        onNodeWithText("freeLimit").assertIsDisplayed()
        onNodeWithText("paidLimit").assertIsDisplayed()
    }

    @Test
    fun `the page groups a dependency module's inputs under their declaring container`() =
        runComposeUiTest {
            setContent { SampleDebugScreen() }

            // Section names, per the ADR-0005 id scheme: an object member's section is the
            // object, a top-level property's is its file.
            onNodeWithText("Tiers").assertIsDisplayed()
            onNodeWithText("Physics").assertIsDisplayed()
        }

    @Test
    fun `the page shows a dependency module's default values`() = runComposeUiTest {
        setContent { SampleDebugScreen() }

        // 10 is speed's default; 25 is Tiers.freeLimit's non-constant `baseLimit * 5`,
        // which only reaches the page if the descriptor read it from the backing field.
        onNodeWithContentDescription("open Physics").performClick()
        onNodeWithText("10").assertIsDisplayed()

        onNodeWithContentDescription("back to debug inputs").performClick()
        onNodeWithContentDescription("open Tiers").performClick()
        onNodeWithText("25").assertIsDisplayed()
    }
}
