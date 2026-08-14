package com.rohittp.debuginput.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.rohittp.debuginput.DebugInputDescriptor
import com.rohittp.debuginput.DebugInputRegistry
import com.rohittp.debuginput.TAG_ENUM
import com.rohittp.debuginput.TAG_INT
import com.rohittp.debuginput.parseTypeSpec

/**
 * The page: one row per debug input in [descriptors], grouped by module and section. Compiler-
 * generated sections are links to dedicated pages, keeping the root a compact table of contents.
 *
 * Each row shows the input's resolved value in an editor chosen by its type spec — a
 * switch for `Boolean`, a dropdown for an enum, a validated field for a number, `Char` or
 * `String`, and a column of element editors with add and remove for a composite. Alongside
 * it: a changed indicator when the override differs from the default, a reset, and — when
 * the input carries `docs` — an info icon that reveals them. A single **Reset all** drops
 * every override, including dormant ones; there is deliberately no per-dormant-override UI
 * (see `docs/adr/0005-id-derivation-and-dormant-overrides.md`).
 * **Copy JSON** writes the effective changed values, their current defaults and source context to
 * the clipboard for a tester-to-developer handoff (ADR-0011).
 *
 * An input whose spec the page cannot parse, or whose value is not the shape its spec
 * claims, gets a disabled row saying no renderer is registered for its type. That is the
 * seam custom renderers will fill.
 *
 * [descriptors] defaults to empty and empty renders nothing, which is the whole release
 * story on Android: the transform does not run there, so this call site is never rewritten
 * to pass anything and the page draws nothing. No other guard.
 *
 * The page fills its parent and expects bounded height. It does not install a
 * [MaterialTheme] of its own, so it takes on the host app's.
 */
@Suppress("DEPRECATION")
@Composable
public fun DebugInputsPage(
    descriptors: List<DebugInputDescriptor> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (descriptors.isEmpty()) return

    val modules = remember(descriptors) { groupDescriptors(descriptors) }
    // A single-module consumer gains nothing from a level of grouping that always
    // reads the same, so collapse it away.
    val showModules = modules.size > 1
    var selectedSectionKey by remember { mutableStateOf<SectionKey?>(null) }
    val selectedSection = selectedSectionKey?.let { key ->
        modules.asSequence().flatMap { it.sections.asSequence() }.firstOrNull { it.key == key }
    }
    val changes by rememberChangedInputs(descriptors)
    val exportJson = remember(changes) { changedValuesJson(changes) }
    val clipboard = LocalClipboardManager.current
    var copiedJson by remember { mutableStateOf<String?>(null) }
    val copyChanges = {
        clipboard.setText(AnnotatedString(exportJson))
        copiedJson = exportJson
    }
    val copied = changes.isNotEmpty() && copiedJson == exportJson

    Surface(modifier = modifier.fillMaxSize()) {
        if (selectedSection == null) {
            InputsRoot(
                modules = modules,
                showModules = showModules,
                changes = changes,
                copied = copied,
                onCopy = copyChanges,
                onSection = { selectedSectionKey = it.key },
            )
        } else {
            SectionPage(
                section = selectedSection,
                showModule = showModules,
                changes = changes,
                copied = copied,
                onCopy = copyChanges,
                onBack = { selectedSectionKey = null },
            )
        }
    }
}

private class ModuleGroup(val module: String, val sections: List<SectionGroup>)

private data class SectionKey(
    val module: String,
    val section: String,
    val sectionPageId: String?,
)

private class SectionGroup(
    val key: SectionKey,
    val section: String,
    val description: String,
    val inputs: List<DebugInputDescriptor>,
) {
    val hasPage: Boolean get() = key.sectionPageId != null
}

/**
 * Aggregation is hierarchical, so a diamond in the consumer's project graph really
 * does hand the page the same input twice. Dedupe by id before anything else: two
 * rows for one input would fight over the same override.
 *
 * Sorting is by module, then section, then display name, with the id last so that
 * the order is total and therefore stable across launches.
 */
private fun groupDescriptors(descriptors: List<DebugInputDescriptor>): List<ModuleGroup> =
    descriptors
        .distinctBy { it.id }
        .sortedWith(compareBy({ it.module }, { it.section }, { it.displayName }, { it.id }))
        .groupBy { it.module }
        .map { (module, inModule) ->
            ModuleGroup(
                module = module,
                sections = inModule
                    .groupBy { SectionKey(module, it.section, it.sectionPageId) }
                    .map { (key, inputs) ->
                        val first = inputs.first()
                        SectionGroup(
                            key = key,
                            section = first.section,
                            description = first.sectionDescription,
                            inputs = inputs,
                        )
                    },
            )
        }

@Composable
private fun InputsRoot(
    modules: List<ModuleGroup>,
    showModules: Boolean,
    changes: List<ChangedInput>,
    copied: Boolean,
    onCopy: () -> Unit,
    onSection: (SectionGroup) -> Unit,
) {
    val changedIds = changes.mapTo(mutableSetOf()) { it.descriptor.id }
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(changes.size, copied, onCopy)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (module in modules) {
                if (showModules) {
                    item(key = "module ${module.module}") { ModuleHeader(module.module) }
                }
                for (section in module.sections) {
                    if (section.hasPage) {
                        item(key = "page ${module.module} ${section.key.sectionPageId}") {
                            SectionLink(
                                section = section,
                                changedCount = section.inputs.count { it.id in changedIds },
                                onClick = { onSection(section) },
                            )
                        }
                    } else {
                        item(key = "section ${module.module} ${section.section}") {
                            SectionHeader(section.section)
                        }
                        items(section.inputs, key = { it.id }) { descriptor ->
                            InputRow(descriptor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionPage(
    section: SectionGroup,
    showModule: Boolean,
    changes: List<ChangedInput>,
    copied: Boolean,
    onCopy: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionPageHeader(
            section = section,
            changedCount = changes.size,
            copied = copied,
            onCopy = onCopy,
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showModule) {
                item(key = "module ${section.key.module}") { ModuleHeader(section.key.module) }
            }
            if (section.description.isNotEmpty()) {
                item(key = "description ${section.key.module} ${section.section}") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = section.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
            items(section.inputs, key = { it.id }) { descriptor ->
                InputRow(descriptor)
            }
        }
    }
}

@Composable
private fun PageHeader(changedCount: Int, copied: Boolean, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
    ) {
        Text(
            text = "Debug inputs",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Tune values live, then hand the changes back to development.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        HeaderActions(changedCount, copied, onCopy)
    }
}

@Composable
private fun SectionPageHeader(
    section: SectionGroup,
    changedCount: Int,
    copied: Boolean,
    onCopy: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "back to debug inputs" },
            ) {
                Text("‹ Back")
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = section.section, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${section.inputs.size} " +
                        if (section.inputs.size == 1) "input" else "inputs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HeaderActions(
            changedCount = changedCount,
            copied = copied,
            onCopy = onCopy,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
        )
    }
}

@Composable
private fun HeaderActions(
    changedCount: Int,
    copied: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onCopy,
            enabled = changedCount > 0,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                if (copied) {
                    "Copied $changedCount ${if (changedCount == 1) "change" else "changes"}"
                } else {
                    "Copy JSON ($changedCount)"
                },
            )
        }
        TextButton(onClick = { DebugInputRegistry.clearAll() }) {
            Text("Reset all")
        }
    }
}

@Composable
private fun ModuleHeader(module: String) {
    Text(
        text = module,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun SectionHeader(section: String) {
    Text(
        text = section,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionLink(section: SectionGroup, changedCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .semantics { contentDescription = "open ${section.section}" },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = section.section,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val count = section.inputs.size
                Text(
                    text = "$count ${if (count == 1) "input" else "inputs"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (changedCount > 0) ChangedBadge("$changedCount changed")
            Spacer(Modifier.width(12.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun InputRow(descriptor: DebugInputDescriptor) {
    // The compiler does not emit `spec` yet, so every descriptor still arrives with an
    // empty one and M1's only type is the right guess. Drop the fallback once it does.
    val specText = descriptor.spec.ifEmpty { TAG_INT }
    // The codec's parser, not a second one: it already rejects an unknown tag, the wrong
    // arity, a container in element position and an enum inside a container, and a page
    // that disagreed with it about any of those would offer the wrong editor for a type
    // that reads back perfectly well.
    val spec = remember(specText) { parseTypeSpec(specText) }
    val constants = descriptor.enumConstants ?: emptyList()

    val override by rememberOverride(descriptor.id, specText)
    val resolved = override ?: descriptor.default
    val elements = if (spec == null || (spec.tag == TAG_ENUM && constants.isEmpty())) {
        // An enum with no constant table has nothing to offer a dropdown, which makes it
        // as unrenderable here as an unknown spec.
        null
    } else {
        elementsOf(resolved, spec)
    }
    val changed = override != null && !sameValue(override, descriptor.default)
    var docsShown by remember(descriptor.id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = descriptor.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = descriptor.typeKey,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (changed) ChangedBadge("changed")
                if (descriptor.docs.isNotEmpty()) {
                    InfoIcon(
                        displayName = descriptor.displayName,
                        onClick = { docsShown = !docsShown },
                    )
                }
                if (override != null) {
                    TextButton(onClick = { DebugInputRegistry.clearOverride(descriptor.id) }) {
                        Text("Reset")
                    }
                }
            }

            if (spec == null || elements == null) {
                MissingRendererField(descriptor)
            } else {
                ValueEditor(
                    id = descriptor.id,
                    displayName = descriptor.displayName,
                    spec = spec,
                    elements = elements,
                    constants = constants,
                    onValue = { DebugInputRegistry.setValue(descriptor.id, it, specText) },
                )
            }

            if (docsShown) {
                Text(
                    text = descriptor.docs,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ChangedBadge(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun rememberChangedInputs(
    descriptors: List<DebugInputDescriptor>,
): State<List<ChangedInput>> {
    val changes = remember(descriptors) { mutableStateOf(changedInputs(descriptors)) }
    DisposableEffect(descriptors) {
        val removers = descriptors.distinctBy { it.id }.map { descriptor ->
            DebugInputRegistry.addListener(descriptor.id) {
                changes.value = changedInputs(descriptors)
            }
        }
        changes.value = changedInputs(descriptors)
        onDispose { removers.forEach { it() } }
    }
    return changes
}

/**
 * The registry has no Compose dependency by design, so the page mirrors the override
 * into Compose state and re-reads on notification. That also covers changes made
 * anywhere other than this row — **Reset all** above, or another page on another
 * screen.
 *
 * Reading through the spec-checked overload rather than trusting the value the listener
 * carries is deliberate twice over: a dormant override stored by an input of another type
 * must not read back as that type, and an array override reads back as the registry's
 * cached instance rather than the one this page handed it (ADR-0009).
 */
@Composable
private fun rememberOverride(id: String, spec: String): State<Any?> {
    val override = remember(id, spec) { mutableStateOf(DebugInputRegistry.overrideOf(id, spec)) }
    DisposableEffect(id, spec) {
        val removeListener = DebugInputRegistry.addListener(id) {
            override.value = DebugInputRegistry.overrideOf(id, spec)
        }
        // A write between the initial read and this registration would be missed
        // otherwise.
        override.value = DebugInputRegistry.overrideOf(id, spec)
        onDispose(removeListener)
    }
    return override
}

/**
 * The row shape an M5 custom renderer will fill. Disabled rather than hidden, because
 * an input the page cannot edit is still worth knowing about.
 */
@Composable
private fun MissingRendererField(descriptor: DebugInputDescriptor) {
    OutlinedTextField(
        value = displayValue(descriptor.default),
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        enabled = false,
        readOnly = true,
        singleLine = true,
        supportingText = { Text("no renderer registered for ${descriptor.typeKey}") },
    )
}

/**
 * An "i" in a circle, drawn from primitives. The Material icon artifacts are not on
 * this module's dependency list and a published UI artifact should not grow one for
 * a single glyph.
 */
@Composable
private fun InfoIcon(displayName: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "docs for $displayName" },
    ) {
        Box(
            modifier = Modifier.size(22.dp).border(1.dp, LocalContentColor.current, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "i", style = MaterialTheme.typography.labelMedium)
        }
    }
}
