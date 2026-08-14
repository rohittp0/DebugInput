package com.rohittp.debuginput.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rohittp.debuginput.TAG_BOOLEAN
import com.rohittp.debuginput.TAG_BYTE
import com.rohittp.debuginput.TAG_CHAR
import com.rohittp.debuginput.TAG_DOUBLE
import com.rohittp.debuginput.TAG_ENUM
import com.rohittp.debuginput.TAG_FLOAT
import com.rohittp.debuginput.TAG_INT
import com.rohittp.debuginput.TAG_LONG
import com.rohittp.debuginput.TAG_PAIR
import com.rohittp.debuginput.TAG_SET
import com.rohittp.debuginput.TAG_SHORT
import com.rohittp.debuginput.TAG_STRING
import com.rohittp.debuginput.TAG_TRIPLE
import com.rohittp.debuginput.TypeSpec
import com.rohittp.debuginput.isContainerTag

/**
 * The editor for one input's value, whatever its shape.
 *
 * Every type is edited through the same state: one text buffer per element. A scalar has
 * one, a `Pair` two, a container as many as it currently holds — which makes validity,
 * resynchronisation and writing one code path rather than fourteen. The renderer a buffer
 * gets is chosen by its element tag: a switch for `bln`, a dropdown for `enm`, a field for
 * the rest.
 *
 * Nothing is written until *every* buffer parses, so a container is never stored
 * half-edited, and text that does not parse stays on screen instead of being reverted
 * under the user's fingers.
 */
@Composable
internal fun ValueEditor(
    id: String,
    displayName: String,
    spec: TypeSpec,
    elements: List<Any?>,
    constants: List<String>,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolvedTexts = remember(elements) { elements.map(::formatScalar) }
    var buffers by remember(id) { mutableStateOf(resolvedTexts) }

    // Follow the resolved value when it moves for a reason other than this editor: a
    // reset, Reset all, or a write from somewhere else. Keyed on the resolved text and
    // guarded on what the buffers parse to, so half-typed text that never reached the
    // registry — "-", "12x", an empty field — is left exactly as it was typed.
    LaunchedEffect(resolvedTexts) {
        if (parseBuffers(buffers, spec, constants) != elements) buffers = resolvedTexts
    }

    fun apply(next: List<String>) {
        buffers = next
        val values = parseBuffers(next, spec, constants) ?: return
        // A Set that swallowed a duplicate would silently shrink on the next read.
        if (spec.tag == TAG_SET && values.distinct().size != values.size) return
        onValue(buildValue(spec, values) ?: return)
    }

    val duplicates = if (spec.tag == TAG_SET) duplicateIndices(buffers, spec, constants) else emptySet()

    fun errorAt(index: Int): String? {
        val tag = elementTagAt(spec, index)
        val text = buffers.getOrElse(index) { "" }
        if (parseScalar(tag, text, constants) == null) {
            val still = if (isContainerTag(spec.tag)) "" else "; still ${resolvedTexts.first()}"
            return invalidMessage(tag, text) + still
        }
        return if (index in duplicates) "already in the set" else null
    }

    if (!isContainerTag(spec.tag)) {
        ElementEditor(
            tag = spec.tag,
            text = buffers.firstOrNull() ?: "",
            onText = { apply(listOf(it)) },
            constants = constants,
            error = errorAt(0),
            description = displayName,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    val variable = isVariableArity(spec.tag)
    Column(
        modifier = modifier.fillMaxWidth().padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        buffers.forEachIndexed { index, text ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = labelFor(spec.tag, index),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(if (variable) 24.dp else 68.dp),
                )
                ElementEditor(
                    tag = elementTagAt(spec, index),
                    text = text,
                    onText = { apply(buffers.replacedAt(index, it)) },
                    constants = constants,
                    error = errorAt(index),
                    description = "$displayName ${index + 1}",
                    modifier = Modifier.weight(1f),
                )
                if (variable) {
                    IconButton(
                        onClick = { apply(buffers.withoutAt(index)) },
                        modifier = Modifier.semantics {
                            contentDescription = "remove $displayName ${index + 1}"
                        },
                    ) {
                        Text(text = "×", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        if (variable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val seed = nextElementText(spec, buffers, constants)
                TextButton(
                    onClick = { seed?.let { apply(buffers + it) } },
                    enabled = seed != null,
                ) {
                    Text("Add")
                }
                if (buffers.isEmpty()) {
                    Text(
                        text = "empty",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** `first`/`second`/`third` for a tuple, the 1-based position for anything else. */
private fun labelFor(tag: String, index: Int): String = when {
    tag != TAG_PAIR && tag != TAG_TRIPLE -> "${index + 1}"
    index == 0 -> "first"
    index == 1 -> "second"
    else -> "third"
}

@Composable
private fun ElementEditor(
    tag: String,
    text: String,
    onText: (String) -> Unit,
    constants: List<String>,
    error: String?,
    description: String,
    modifier: Modifier = Modifier,
) {
    when (tag) {
        TAG_BOOLEAN -> Box(modifier) {
            Switch(
                checked = text == TRUE_TEXT,
                onCheckedChange = { onText(it.toString()) },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }

        TAG_ENUM -> EnumDropdown(
            selected = text,
            constants = constants,
            onSelect = onText,
            description = description,
            modifier = modifier,
        )

        else -> OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = modifier,
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardTypeOf(tag)),
            trailingIcon = if (isNumericTag(tag)) {
                { NegateButton(text = text, onText = onText, description = description) }
            } else {
                null
            },
            supportingText = error?.let { { Text(it) } },
        )
    }
}

@Composable
private fun EnumDropdown(
    selected: String,
    constants: List<String>,
    onSelect: (String) -> Unit,
    description: String,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "choose $description" },
        ) {
            Text(text = selected, modifier = Modifier.weight(1f))
            Text(text = "▼", style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (constant in constants) {
                DropdownMenuItem(
                    text = { Text(constant) },
                    onClick = {
                        open = false
                        onSelect(constant)
                    },
                )
            }
        }
    }
}

/**
 * Flips the sign of a numeric field.
 *
 * Not decoration: `KeyboardType.Number` and `KeyboardType.Decimal` are a UIKit number pad
 * on iOS, and that pad has no minus key, so without this a negative value cannot be typed
 * at all.
 */
@Composable
private fun NegateButton(text: String, onText: (String) -> Unit, description: String) {
    IconButton(
        onClick = { onText(if (text.startsWith("-")) text.substring(1) else "-$text") },
        modifier = Modifier.semantics { contentDescription = "negate $description" },
    ) {
        Text(text = "±", style = MaterialTheme.typography.titleMedium)
    }
}

private fun keyboardTypeOf(tag: String): KeyboardType = when (tag) {
    TAG_INT, TAG_LONG, TAG_SHORT, TAG_BYTE -> KeyboardType.Number
    TAG_FLOAT, TAG_DOUBLE -> KeyboardType.Decimal
    else -> KeyboardType.Text
}

private fun isNumericTag(tag: String): Boolean = when (tag) {
    TAG_INT, TAG_LONG, TAG_SHORT, TAG_BYTE, TAG_FLOAT, TAG_DOUBLE -> true
    else -> false
}

private fun List<String>.replacedAt(index: Int, text: String): List<String> =
    mapIndexed { at, existing -> if (at == index) text else existing }

private fun List<String>.withoutAt(index: Int): List<String> =
    filterIndexed { at, _ -> at != index }

/** Every buffer's value, or null when any one of them does not parse. */
private fun parseBuffers(
    buffers: List<String>,
    spec: TypeSpec,
    constants: List<String>,
): List<Any?>? {
    val values = ArrayList<Any?>(buffers.size)
    buffers.forEachIndexed { index, text ->
        values += parseScalar(elementTagAt(spec, index), text, constants) ?: return null
    }
    return values
}

/** The positions of every element a `Set` already holds earlier in the list. */
private fun duplicateIndices(
    buffers: List<String>,
    spec: TypeSpec,
    constants: List<String>,
): Set<Int> {
    val seen = mutableSetOf<Any?>()
    val duplicates = mutableSetOf<Int>()
    buffers.forEachIndexed { index, text ->
        val value = parseScalar(elementTagAt(spec, index), text, constants) ?: return@forEachIndexed
        if (!seen.add(value)) duplicates += index
    }
    return duplicates
}

/**
 * The text to seed a newly added element with, or null when there is nothing left to add
 * — which only happens to a `Set<Boolean>` holding both constants.
 *
 * A seed that is already in a `Set` would be refused the moment it appeared, leaving the
 * user to fix an element they did not type, so sets get the first candidate they do not
 * already hold.
 */
private fun nextElementText(
    spec: TypeSpec,
    buffers: List<String>,
    constants: List<String>,
): String? {
    val tag = elementTagAt(spec, buffers.size)
    if (spec.tag != TAG_SET) return zeroText(tag, constants)

    val taken = buffers.mapNotNull { parseScalar(tag, it, constants) }.toSet()
    return seedCandidates(tag, constants).firstOrNull { candidate ->
        parseScalar(tag, candidate, constants)?.let { it !in taken } == true
    }
}

private fun seedCandidates(tag: String, constants: List<String>): Sequence<String> = when (tag) {
    TAG_BOOLEAN -> sequenceOf(FALSE_TEXT, TRUE_TEXT)
    TAG_CHAR -> ('a'..'z').asSequence().map { it.toString() }
    TAG_STRING -> sequenceOf("") + (1..SEED_LIMIT).asSequence().map { it.toString() }
    TAG_FLOAT, TAG_DOUBLE -> (0..SEED_LIMIT).asSequence().map { "$it.0" }
    TAG_ENUM -> constants.asSequence()
    else -> (0..SEED_LIMIT).asSequence().map { it.toString() }
}

// Comfortably past any container anyone edits by hand, and inside Byte's range so that
// the integral candidates are valid for every integral tag.
private const val SEED_LIMIT = 64

private fun zeroText(tag: String, constants: List<String>): String = when (tag) {
    TAG_FLOAT, TAG_DOUBLE -> "0.0"
    TAG_BOOLEAN -> FALSE_TEXT
    TAG_CHAR -> "a"
    TAG_STRING -> ""
    TAG_ENUM -> constants.firstOrNull() ?: ""
    else -> "0"
}

private const val TRUE_TEXT = "true"
private const val FALSE_TEXT = "false"
private const val NOT_A_NUMBER = "NaN"
private const val POSITIVE_INFINITY = "Infinity"
private const val NEGATIVE_INFINITY = "-Infinity"

/**
 * One element's value, or null when [text] is not one.
 *
 * Each integral type parses with its own parser, so `40000` in a `Short` field is rejected
 * rather than wrapped. Floating point mirrors the codec exactly: the three specials by
 * name, and a literal that overflows to a non-finite value refused — the codec would
 * refuse to store it, and a field that appeared to accept it would be lying.
 */
internal fun parseScalar(tag: String, text: String, constants: List<String>): Any? = when (tag) {
    TAG_INT -> text.toIntOrNull()
    TAG_LONG -> text.toLongOrNull()
    TAG_SHORT -> text.toShortOrNull()
    TAG_BYTE -> text.toByteOrNull()
    TAG_FLOAT -> when (text) {
        NOT_A_NUMBER -> Float.NaN
        POSITIVE_INFINITY -> Float.POSITIVE_INFINITY
        NEGATIVE_INFINITY -> Float.NEGATIVE_INFINITY
        else -> text.toFloatOrNull()?.takeIf { it.isFinite() }
    }

    TAG_DOUBLE -> when (text) {
        NOT_A_NUMBER -> Double.NaN
        POSITIVE_INFINITY -> Double.POSITIVE_INFINITY
        NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY
        else -> text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    // A switch only ever writes one of two texts, so this cannot fail.
    TAG_BOOLEAN -> text == TRUE_TEXT
    // A Kotlin Char is one UTF-16 unit, and the codec refuses a payload of any other
    // length, so a two-character field must not be written.
    TAG_CHAR -> text.singleOrNull()
    TAG_STRING -> text
    TAG_ENUM -> text.takeIf { it in constants }
    else -> null
}

/** The text form of a value, matching what [parseScalar] reads back. */
internal fun formatScalar(value: Any?): String = when (value) {
    is Float -> when {
        value.isNaN() -> NOT_A_NUMBER
        value == Float.POSITIVE_INFINITY -> POSITIVE_INFINITY
        value == Float.NEGATIVE_INFINITY -> NEGATIVE_INFINITY
        else -> value.toString()
    }

    is Double -> when {
        value.isNaN() -> NOT_A_NUMBER
        value == Double.POSITIVE_INFINITY -> POSITIVE_INFINITY
        value == Double.NEGATIVE_INFINITY -> NEGATIVE_INFINITY
        else -> value.toString()
    }

    is Enum<*> -> value.name
    null -> ""
    else -> value.toString()
}

private fun invalidMessage(tag: String, text: String): String = when (tag) {
    TAG_INT, TAG_LONG, TAG_SHORT, TAG_BYTE ->
        // "must be a whole number" is baffling when you have just typed one, so a value
        // that is only out of range says so and names the range it missed.
        if (looksWhole(text)) "must be a whole number in ${rangeOf(tag)}" else "must be a whole number"

    TAG_FLOAT, TAG_DOUBLE -> "must be a finite number, NaN, Infinity or -Infinity"
    TAG_CHAR -> "must be exactly one character"
    TAG_ENUM -> "must be one of the listed constants"
    else -> "cannot be edited"
}

private fun looksWhole(text: String): Boolean {
    val digits = if (text.startsWith("-") || text.startsWith("+")) text.substring(1) else text
    return digits.isNotEmpty() && digits.all { it in '0'..'9' }
}

private fun rangeOf(tag: String): String = when (tag) {
    TAG_LONG -> "${Long.MIN_VALUE}..${Long.MAX_VALUE}"
    TAG_SHORT -> "${Short.MIN_VALUE}..${Short.MAX_VALUE}"
    TAG_BYTE -> "${Byte.MIN_VALUE}..${Byte.MAX_VALUE}"
    else -> "${Int.MIN_VALUE}..${Int.MAX_VALUE}"
}
