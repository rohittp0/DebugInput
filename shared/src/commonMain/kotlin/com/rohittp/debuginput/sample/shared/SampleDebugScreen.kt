package com.rohittp.debuginput.sample.shared

import androidx.compose.runtime.Composable
import com.rohittp.debuginput.compose.DebugInputsPage

/**
 * The `DebugInputsPage()` call site. This module is where the IR plugin injects the
 * aggregated descriptor list, so it must be a module that can see every module whose
 * inputs should appear — here, `:domain`.
 * See docs/adr/0006-linkage-by-call-site-rewriting.md.
 */
@Composable
fun SampleDebugScreen() {
    DebugInputsPage()
}
