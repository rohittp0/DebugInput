package com.rohittp.debuginput.compose

import com.rohittp.debuginput.TAG_ARRAY
import com.rohittp.debuginput.TAG_BOOLEAN
import com.rohittp.debuginput.TAG_BOOLEAN_ARRAY
import com.rohittp.debuginput.TAG_BYTE
import com.rohittp.debuginput.TAG_BYTE_ARRAY
import com.rohittp.debuginput.TAG_CHAR
import com.rohittp.debuginput.TAG_CHAR_ARRAY
import com.rohittp.debuginput.TAG_DOUBLE
import com.rohittp.debuginput.TAG_DOUBLE_ARRAY
import com.rohittp.debuginput.TAG_ENUM
import com.rohittp.debuginput.TAG_FLOAT
import com.rohittp.debuginput.TAG_FLOAT_ARRAY
import com.rohittp.debuginput.TAG_INT
import com.rohittp.debuginput.TAG_INT_ARRAY
import com.rohittp.debuginput.TAG_LIST
import com.rohittp.debuginput.TAG_LONG
import com.rohittp.debuginput.TAG_LONG_ARRAY
import com.rohittp.debuginput.TAG_PAIR
import com.rohittp.debuginput.TAG_SET
import com.rohittp.debuginput.TAG_SHORT
import com.rohittp.debuginput.TAG_SHORT_ARRAY
import com.rohittp.debuginput.TAG_STRING
import com.rohittp.debuginput.TAG_TRIPLE
import com.rohittp.debuginput.TypeSpec
import com.rohittp.debuginput.fixedArity
import com.rohittp.debuginput.impliedElementTag
import com.rohittp.debuginput.isContainerTag

/**
 * What the page has to know about a value beyond its type spec: how to take one apart into
 * the elements an editor edits, and how to put one back together to hand the registry.
 *
 * The spec vocabulary itself — the tags, [isContainerTag], [impliedElementTag],
 * [fixedArity] — belongs to the codec and is used from there. Two copies of it would let
 * the page offer the wrong editor for a type that reads back perfectly well.
 */

/** Containers whose element count the page can change. `Pair` and `Triple` cannot. */
internal fun isVariableArity(tag: String): Boolean = isContainerTag(tag) && fixedArity(tag) < 0

/** Which scalar renderer the element at [index] needs. */
internal fun elementTagAt(spec: TypeSpec, index: Int): String = when {
    spec.arguments.size == 1 -> spec.arguments[0].tag
    spec.arguments.isNotEmpty() -> spec.arguments[index.coerceIn(spec.arguments.indices)].tag
    // A primitive array's element type is implicit in its tag: `iarr` takes no arguments.
    else -> impliedElementTag(spec.tag) ?: spec.tag
}

/**
 * Unwraps a resolved value into the elements the editors work on — one for a scalar, one
 * per element for a composite — or null when the value is not [spec]'s shape at all.
 *
 * Null is not a corner case to be papered over: it is how an input whose descriptor and
 * value disagree lands on the "no renderer registered" row instead of on an editor that
 * cannot show it.
 */
internal fun elementsOf(value: Any?, spec: TypeSpec): List<Any?>? {
    if (!isContainerTag(spec.tag)) {
        val scalar = if (spec.tag == TAG_ENUM) enumNameOf(value) else value
        return if (matchesTag(scalar, spec.tag)) listOf(scalar) else null
    }

    val elements = when (spec.tag) {
        TAG_LIST -> (value as? List<*>)
        TAG_SET -> (value as? Set<*>)?.toList()
        TAG_ARRAY -> (value as? Array<*>)?.toList()
        TAG_PAIR -> (value as? Pair<*, *>)?.let { listOf(it.first, it.second) }
        TAG_TRIPLE -> (value as? Triple<*, *, *>)?.let { listOf(it.first, it.second, it.third) }
        TAG_INT_ARRAY -> (value as? IntArray)?.toList()
        TAG_LONG_ARRAY -> (value as? LongArray)?.toList()
        TAG_SHORT_ARRAY -> (value as? ShortArray)?.toList()
        TAG_BYTE_ARRAY -> (value as? ByteArray)?.toList()
        TAG_FLOAT_ARRAY -> (value as? FloatArray)?.toList()
        TAG_DOUBLE_ARRAY -> (value as? DoubleArray)?.toList()
        TAG_BOOLEAN_ARRAY -> (value as? BooleanArray)?.toList()
        TAG_CHAR_ARRAY -> (value as? CharArray)?.toList()
        else -> null
    } ?: return null

    val required = fixedArity(spec.tag)
    if (required >= 0 && elements.size != required) return null
    elements.forEachIndexed { index, element ->
        if (!matchesTag(element, elementTagAt(spec, index))) return null
    }
    return elements
}

/**
 * Builds the value to hand [com.rohittp.debuginput.DebugInputRegistry.setValue], or null
 * when [values] cannot make one.
 *
 * Every array here is a **new** instance. Mutating the one a read returned would not
 * reach the override store and would leave the page showing something no read agrees
 * with — see docs/adr/0009-array-inputs-return-a-cached-instance.md.
 */
internal fun buildValue(spec: TypeSpec, values: List<Any?>): Any? = when (spec.tag) {
    TAG_LIST -> values.toList()
    TAG_SET -> values.toSet()
    TAG_ARRAY -> values.toTypedArray()
    TAG_PAIR -> if (values.size == 2) Pair(values[0], values[1]) else null
    TAG_TRIPLE -> if (values.size == 3) Triple(values[0], values[1], values[2]) else null
    TAG_INT_ARRAY -> IntArray(values.size) { values[it] as Int }
    TAG_LONG_ARRAY -> LongArray(values.size) { values[it] as Long }
    TAG_SHORT_ARRAY -> ShortArray(values.size) { values[it] as Short }
    TAG_BYTE_ARRAY -> ByteArray(values.size) { values[it] as Byte }
    TAG_FLOAT_ARRAY -> FloatArray(values.size) { values[it] as Float }
    TAG_DOUBLE_ARRAY -> DoubleArray(values.size) { values[it] as Double }
    TAG_BOOLEAN_ARRAY -> BooleanArray(values.size) { values[it] as Boolean }
    TAG_CHAR_ARRAY -> CharArray(values.size) { values[it] as Char }
    else -> values.singleOrNull()
}

/**
 * Whether two resolved values are the same value, which drives the changed indicator.
 *
 * `==` alone would not do: arrays compare by identity, so every array override would show
 * as changed even when its contents match the default. An enum override comes back as a
 * constant name while the default is the constant itself, so both collapse to the name.
 */
internal fun sameValue(a: Any?, b: Any?): Boolean = comparable(a) == comparable(b)

/** A value in a form fit to print in a row the page cannot edit. */
internal fun displayValue(value: Any?): String = comparable(value)?.toString() ?: ""

private fun comparable(value: Any?): Any? = when (value) {
    is Enum<*> -> value.name
    is IntArray -> value.toList()
    is LongArray -> value.toList()
    is ShortArray -> value.toList()
    is ByteArray -> value.toList()
    is FloatArray -> value.toList()
    is DoubleArray -> value.toList()
    is BooleanArray -> value.toList()
    is CharArray -> value.toList()
    is Array<*> -> value.toList()
    else -> value
}

private fun enumNameOf(value: Any?): Any? = (value as? Enum<*>)?.name ?: value

private fun matchesTag(value: Any?, tag: String): Boolean = when (tag) {
    TAG_INT -> value is Int
    TAG_LONG -> value is Long
    TAG_SHORT -> value is Short
    TAG_BYTE -> value is Byte
    TAG_FLOAT -> value is Float
    TAG_DOUBLE -> value is Double
    TAG_BOOLEAN -> value is Boolean
    TAG_CHAR -> value is Char
    TAG_STRING -> value is String
    // The wire form holds a constant's name and the descriptor's default holds the
    // constant, so either is a legitimate enum value here.
    TAG_ENUM -> value is String
    else -> false
}
