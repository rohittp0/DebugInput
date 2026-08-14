package com.rohittp.debuginput.compose

import com.rohittp.debuginput.DebugInputDescriptor
import com.rohittp.debuginput.DebugInputRegistry
import com.rohittp.debuginput.TAG_INT

/** One effective override that differs from the declaration's current default. */
internal class ChangedInput(
    val descriptor: DebugInputDescriptor,
    val value: Any?,
)

/**
 * The current changes in stable id order. Spec-checked reads deliberately exclude dormant
 * overrides left behind by a declaration whose type changed.
 */
internal fun changedInputs(descriptors: List<DebugInputDescriptor>): List<ChangedInput> =
    descriptors
        .distinctBy { it.id }
        .sortedBy { it.id }
        .mapNotNull { descriptor ->
            val spec = descriptor.spec.ifEmpty { TAG_INT }
            val value = DebugInputRegistry.overrideOf(descriptor.id, spec) ?: return@mapNotNull null
            if (sameValue(value, descriptor.default)) null else ChangedInput(descriptor, value)
        }

/**
 * A versioned handoff payload for turning tester overrides into source defaults. No JSON library is
 * used so the published Compose artifact does not acquire a serialization dependency.
 */
internal fun changedValuesJson(changes: List<ChangedInput>): String = buildString {
    append("{\n  \"version\": 1,\n  \"changes\": [")
    changes.forEachIndexed { index, change ->
        if (index == 0) append('\n') else append(",\n")
        val descriptor = change.descriptor
        append("    {\n")
        appendJsonField("id", descriptor.id, trailingComma = true)
        appendJsonField("module", descriptor.module, trailingComma = true)
        appendJsonField("section", descriptor.section, trailingComma = true)
        appendJsonField("name", descriptor.displayName, trailingComma = true)
        appendJsonField("type", descriptor.typeKey, trailingComma = true)
        append("      \"default\": ")
        appendJsonValue(descriptor.default)
        append(",\n      \"value\": ")
        appendJsonValue(change.value)
        append("\n    }")
    }
    if (changes.isNotEmpty()) append('\n')
    append("  ]\n}")
}

private fun StringBuilder.appendJsonField(name: String, value: String, trailingComma: Boolean) {
    append("      ")
    appendJsonString(name)
    append(": ")
    appendJsonString(value)
    if (trailingComma) append(',')
    append('\n')
}

private fun StringBuilder.appendJsonValue(value: Any?) {
    when (value) {
        null -> append("null")
        is Boolean, is Byte, is Short, is Int, is Long -> append(value.toString())
        is Float -> appendJsonFloatingPoint(value, value.isFinite())
        is Double -> appendJsonFloatingPoint(value, value.isFinite())
        is Char -> appendJsonString(value.toString())
        is String -> appendJsonString(value)
        is Enum<*> -> appendJsonString(value.name)
        is IntArray -> appendJsonArray(value.toList())
        is LongArray -> appendJsonArray(value.toList())
        is ShortArray -> appendJsonArray(value.toList())
        is ByteArray -> appendJsonArray(value.toList())
        is FloatArray -> appendJsonArray(value.toList())
        is DoubleArray -> appendJsonArray(value.toList())
        is BooleanArray -> appendJsonArray(value.toList())
        is CharArray -> appendJsonArray(value.toList())
        is Array<*> -> appendJsonArray(value.toList())
        is Iterable<*> -> appendJsonArray(value.toList())
        is Pair<*, *> -> appendJsonArray(listOf(value.first, value.second))
        is Triple<*, *, *> -> appendJsonArray(listOf(value.first, value.second, value.third))
        else -> appendJsonString(value.toString())
    }
}

private fun StringBuilder.appendJsonFloatingPoint(value: Any, finite: Boolean) {
    if (finite) append(value.toString()) else appendJsonString(value.toString())
}

private fun StringBuilder.appendJsonArray(values: List<*>) {
    append('[')
    values.forEachIndexed { index, value ->
        if (index > 0) append(", ")
        appendJsonValue(value)
    }
    append(']')
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
