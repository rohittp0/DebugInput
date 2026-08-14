package com.rohittp.debuginput.compiler.ir

import com.rohittp.debuginput.compiler.descriptorFunctionFqName
import java.io.File

/**
 * The descriptor manifest: the JSON side output the Gradle plugin reads to learn which
 * modules were instrumented and what descriptor function to name for each.
 *
 * It is written even when the module has no inputs, because "instrumented, nothing declared"
 * and "never instrumented" have to be distinguishable on the Gradle side.
 *
 * Hand-rolled rather than serialized: this module already links the whole Kotlin compiler,
 * and one more dependency on the compiler plugin's classpath is a version conflict waiting
 * to happen in somebody else's Kotlin daemon.
 */
internal fun writeDescriptorManifest(
    manifestOut: String,
    module: String,
    sites: List<DebugInputSite>,
) {
    val target = File(manifestOut)
    target.parentFile?.mkdirs()
    target.writeText(descriptorManifestJson(module, sites))
}

internal fun descriptorManifestJson(module: String, sites: List<DebugInputSite>): String =
    buildString {
        append("{\"module\":")
        appendJsonString(module)
        append(",\"function\":")
        appendJsonString(descriptorFunctionFqName(module))
        append(",\"inputs\":[")
        sites.forEachIndexed { index, site ->
            if (index > 0) append(',')
            append("{\"id\":")
            appendJsonString(site.id)
            append(",\"typeKey\":")
            appendJsonString(site.typeKey)
            append('}')
        }
        append("]}")
    }

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (character < ' ') {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
