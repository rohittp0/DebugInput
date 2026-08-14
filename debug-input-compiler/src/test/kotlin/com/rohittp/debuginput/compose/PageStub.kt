package com.rohittp.debuginput.compose

import com.rohittp.debuginput.DebugInputDescriptor

/**
 * Stand-in for `debug-input-compose`'s page, which these tests cannot depend on: that module
 * is Compose Multiplatform with no JVM target, and `@Composable` would need the Compose
 * compiler plugin on top of ours.
 *
 * The plugin matches the call site by package and name and fills the parameter called
 * `descriptors`, so only those three things have to agree with the real signature. `modifier`
 * stands in for `androidx.compose.ui.Modifier`, which is not on this classpath.
 */
fun DebugInputsPage(
    descriptors: List<DebugInputDescriptor> = emptyList(),
    modifier: Any? = null,
) {
    PageRenders.descriptors += descriptors
    PageRenders.modifiers += modifier
}

/** What the rewritten call site actually handed the page. */
object PageRenders {

    val descriptors: MutableList<List<DebugInputDescriptor>> = mutableListOf()

    val modifiers: MutableList<Any?> = mutableListOf()

    fun reset() {
        descriptors.clear()
        modifiers.clear()
    }
}
