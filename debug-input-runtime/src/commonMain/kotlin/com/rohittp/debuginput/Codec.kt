package com.rohittp.debuginput

/**
 * The override wire format: every value, scalar or composite, is framed identically as
 * `<tag>:<length>:<payload>`, where `length` is the payload's char count. A container
 * puts its encoded elements in the payload back to back, so the element count falls out
 * of parsing until the payload is consumed and is never stored — a stored count is a
 * second source of truth that can disagree with the length.
 * See docs/adr/0008-length-prefixed-self-describing-encoding.md.
 *
 * Length prefixes mean no escaping: a payload containing `:` needs no special handling.
 *
 * Nothing here throws. Everything it reads came off disk, possibly written by an older
 * build of the app, and a value that cannot be read is an ignored dormant override
 * rather than a crash — docs/adr/0005-id-derivation-and-dormant-overrides.md.
 */

@DebugInputInternalApi
public const val TAG_INT: String = "int"
@DebugInputInternalApi
public const val TAG_LONG: String = "lng"
@DebugInputInternalApi
public const val TAG_SHORT: String = "sht"
@DebugInputInternalApi
public const val TAG_BYTE: String = "byt"
@DebugInputInternalApi
public const val TAG_FLOAT: String = "flt"
@DebugInputInternalApi
public const val TAG_DOUBLE: String = "dbl"
@DebugInputInternalApi
public const val TAG_BOOLEAN: String = "bln"
@DebugInputInternalApi
public const val TAG_CHAR: String = "chr"
@DebugInputInternalApi
public const val TAG_STRING: String = "str"
@DebugInputInternalApi
public const val TAG_ENUM: String = "enm"

@DebugInputInternalApi
public const val TAG_LIST: String = "lst"
@DebugInputInternalApi
public const val TAG_SET: String = "set"
@DebugInputInternalApi
public const val TAG_ARRAY: String = "arr"
@DebugInputInternalApi
public const val TAG_PAIR: String = "pair"
@DebugInputInternalApi
public const val TAG_TRIPLE: String = "trip"

@DebugInputInternalApi
public const val TAG_INT_ARRAY: String = "iarr"
@DebugInputInternalApi
public const val TAG_LONG_ARRAY: String = "larr"
@DebugInputInternalApi
public const val TAG_SHORT_ARRAY: String = "sarr"
@DebugInputInternalApi
public const val TAG_BYTE_ARRAY: String = "barr"
@DebugInputInternalApi
public const val TAG_FLOAT_ARRAY: String = "farr"
@DebugInputInternalApi
public const val TAG_DOUBLE_ARRAY: String = "darr"
@DebugInputInternalApi
public const val TAG_BOOLEAN_ARRAY: String = "zarr"
@DebugInputInternalApi
public const val TAG_CHAR_ARRAY: String = "carr"

/**
 * The parsed form of a codec spec literal such as `lst<str>` or `pair<int,str>`.
 *
 * Public, behind [DebugInputInternalApi], because `debug-input-compose` dispatches its
 * renderers on the same grammar. Reimplementing the parser there would put the format's
 * riskiest logic in two places, which is exactly what a single self-describing wire form
 * was chosen to avoid — see docs/adr/0008-length-prefixed-self-describing-encoding.md.
 */
@DebugInputInternalApi
public class TypeSpec(public val tag: String, public val arguments: List<TypeSpec>) {
    override fun toString(): String =
        if (arguments.isEmpty()) tag else tag + arguments.joinToString(",", "<", ">")
}

// ---- Spec literals ----

/**
 * Parses a spec literal. Returns null for an unknown tag, the wrong number of
 * arguments, an argument that is not a scalar (one nesting level only), an enum inside
 * a container (its constant table cannot travel in a spec string), or any syntax the
 * grammar does not produce.
 */
@DebugInputInternalApi
public fun parseTypeSpec(spec: String): TypeSpec? = parseSpec(spec, 0, spec.length, depth = 0)

private fun parseSpec(spec: String, from: Int, to: Int, depth: Int): TypeSpec? {
    if (depth > 1 || to <= from) return null

    val open = spec.indexOf('<', from)
    if (open < 0 || open >= to) {
        val tag = spec.substring(from, to)
        return if (arityOf(tag) == 0) TypeSpec(tag, emptyList()) else null
    }

    if (spec[to - 1] != '>') return null
    val tag = spec.substring(from, open)
    val arity = arityOf(tag)
    if (arity <= 0) return null

    val argumentsEnd = to - 1
    var start = open + 1
    if (argumentsEnd <= start) return null

    val arguments = mutableListOf<TypeSpec>()
    var nesting = 0
    for (i in start until argumentsEnd) {
        when (spec[i]) {
            '<' -> nesting++
            '>' -> if (--nesting < 0) return null
            ',' -> if (nesting == 0) {
                arguments += parseSpec(spec, start, i, depth + 1) ?: return null
                start = i + 1
            }
        }
    }
    if (nesting != 0) return null
    arguments += parseSpec(spec, start, argumentsEnd, depth + 1) ?: return null

    if (arguments.size != arity) return null
    // One nesting level, and no enums inside containers. The test is whether an argument
    // is container-*tagged*, not whether it has arguments: the eight primitive arrays are
    // containers that take none, so `lst<iarr>` would otherwise parse here while the value
    // parser refuses a container frame below the top level — an override that could be
    // written and then never read by anything.
    if (arguments.any { isContainerTag(it.tag) || it.tag == TAG_ENUM }) return null
    return TypeSpec(tag, arguments)
}

/** How many type arguments [tag] takes, or -1 when the tag is not one of ours. */
private fun arityOf(tag: String): Int = when (tag) {
    TAG_INT, TAG_LONG, TAG_SHORT, TAG_BYTE, TAG_FLOAT, TAG_DOUBLE,
    TAG_BOOLEAN, TAG_CHAR, TAG_STRING, TAG_ENUM,
    TAG_INT_ARRAY, TAG_LONG_ARRAY, TAG_SHORT_ARRAY, TAG_BYTE_ARRAY,
    TAG_FLOAT_ARRAY, TAG_DOUBLE_ARRAY, TAG_BOOLEAN_ARRAY, TAG_CHAR_ARRAY,
    -> 0

    TAG_LIST, TAG_SET, TAG_ARRAY -> 1
    TAG_PAIR -> 2
    TAG_TRIPLE -> 3
    else -> -1
}

/** Every tag that frames elements, primitive arrays included. */
@DebugInputInternalApi
public fun isContainerTag(tag: String): Boolean = when (tag) {
    TAG_LIST, TAG_SET, TAG_ARRAY, TAG_PAIR, TAG_TRIPLE,
    TAG_INT_ARRAY, TAG_LONG_ARRAY, TAG_SHORT_ARRAY, TAG_BYTE_ARRAY,
    TAG_FLOAT_ARRAY, TAG_DOUBLE_ARRAY, TAG_BOOLEAN_ARRAY, TAG_CHAR_ARRAY,
    -> true

    else -> false
}

/** Every array-tagged spec, whose reads are cached per id — see ADR-0009. */
@DebugInputInternalApi
public fun isArrayTag(tag: String): Boolean = when (tag) {
    TAG_ARRAY,
    TAG_INT_ARRAY, TAG_LONG_ARRAY, TAG_SHORT_ARRAY, TAG_BYTE_ARRAY,
    TAG_FLOAT_ARRAY, TAG_DOUBLE_ARRAY, TAG_BOOLEAN_ARRAY, TAG_CHAR_ARRAY,
    -> true

    else -> false
}

/** The element tag a primitive array's frames must carry, or null for other tags. */
@DebugInputInternalApi
public fun impliedElementTag(tag: String): String? = when (tag) {
    TAG_INT_ARRAY -> TAG_INT
    TAG_LONG_ARRAY -> TAG_LONG
    TAG_SHORT_ARRAY -> TAG_SHORT
    TAG_BYTE_ARRAY -> TAG_BYTE
    TAG_FLOAT_ARRAY -> TAG_FLOAT
    TAG_DOUBLE_ARRAY -> TAG_DOUBLE
    TAG_BOOLEAN_ARRAY -> TAG_BOOLEAN
    TAG_CHAR_ARRAY -> TAG_CHAR
    else -> null
}

/** The number of elements [tag] must hold, or -1 when any number will do. */
@DebugInputInternalApi
public fun fixedArity(tag: String): Int = when (tag) {
    TAG_PAIR -> 2
    TAG_TRIPLE -> 3
    else -> -1
}

// ---- Encoding ----

/** Encodes [value] as [spec], or null when the value is not that shape. */
internal fun encodeValue(value: Any?, spec: TypeSpec): String? {
    val payload = encodePayload(value, spec) ?: return null
    return frame(spec.tag, payload)
}

private fun frame(tag: String, payload: String): String = "$tag:${payload.length}:$payload"

private fun encodePayload(value: Any?, spec: TypeSpec): String? = when (spec.tag) {
    TAG_INT -> (value as? Int)?.toString()
    TAG_LONG -> (value as? Long)?.toString()
    TAG_SHORT -> (value as? Short)?.toString()
    TAG_BYTE -> (value as? Byte)?.toString()
    TAG_FLOAT -> (value as? Float)?.let(::encodeFloatPayload)
    TAG_DOUBLE -> (value as? Double)?.let(::encodeDoublePayload)
    TAG_BOOLEAN -> (value as? Boolean)?.toString()
    TAG_CHAR -> (value as? Char)?.toString()
    TAG_STRING -> value as? String
    // The page may hand over either the constant or its name.
    TAG_ENUM -> (value as? Enum<*>)?.name ?: value as? String

    TAG_LIST -> (value as? List<*>)?.let { encodeElements(it, spec.arguments[0]) }
    TAG_SET -> (value as? Set<*>)?.let { encodeElements(it, spec.arguments[0]) }
    TAG_ARRAY -> (value as? Array<*>)?.let { encodeElements(it.asList(), spec.arguments[0]) }
    TAG_PAIR -> (value as? Pair<*, *>)?.let {
        encodeElementsBySpec(listOf(it.first, it.second), spec.arguments)
    }

    TAG_TRIPLE -> (value as? Triple<*, *, *>)?.let {
        encodeElementsBySpec(listOf(it.first, it.second, it.third), spec.arguments)
    }

    TAG_INT_ARRAY -> (value as? IntArray)?.let { encodeElements(it.toList(), INT_SPEC) }
    TAG_LONG_ARRAY -> (value as? LongArray)?.let { encodeElements(it.toList(), LONG_SPEC) }
    TAG_SHORT_ARRAY -> (value as? ShortArray)?.let { encodeElements(it.toList(), SHORT_SPEC) }
    TAG_BYTE_ARRAY -> (value as? ByteArray)?.let { encodeElements(it.toList(), BYTE_SPEC) }
    TAG_FLOAT_ARRAY -> (value as? FloatArray)?.let { encodeElements(it.toList(), FLOAT_SPEC) }
    TAG_DOUBLE_ARRAY -> (value as? DoubleArray)?.let { encodeElements(it.toList(), DOUBLE_SPEC) }
    TAG_BOOLEAN_ARRAY -> (value as? BooleanArray)?.let { encodeElements(it.toList(), BOOLEAN_SPEC) }
    TAG_CHAR_ARRAY -> (value as? CharArray)?.let { encodeElements(it.toList(), CHAR_SPEC) }

    else -> null
}

private fun encodeElements(elements: Collection<Any?>, elementSpec: TypeSpec): String? {
    val encoded = StringBuilder()
    for (element in elements) encoded.append(encodeValue(element, elementSpec) ?: return null)
    return encoded.toString()
}

private fun encodeElementsBySpec(elements: List<Any?>, specs: List<TypeSpec>): String? {
    if (elements.size != specs.size) return null
    val encoded = StringBuilder()
    for (i in elements.indices) encoded.append(encodeValue(elements[i], specs[i]) ?: return null)
    return encoded.toString()
}

// A special's text is fixed here rather than left to Float.toString(), so the same
// three payloads are written and read on every platform.
private fun encodeFloatPayload(value: Float): String = when {
    value.isNaN() -> NOT_A_NUMBER
    value == Float.POSITIVE_INFINITY -> POSITIVE_INFINITY
    value == Float.NEGATIVE_INFINITY -> NEGATIVE_INFINITY
    else -> value.toString()
}

private fun encodeDoublePayload(value: Double): String = when {
    value.isNaN() -> NOT_A_NUMBER
    value == Double.POSITIVE_INFINITY -> POSITIVE_INFINITY
    value == Double.NEGATIVE_INFINITY -> NEGATIVE_INFINITY
    else -> value.toString()
}

private const val NOT_A_NUMBER = "NaN"
private const val POSITIVE_INFINITY = "Infinity"
private const val NEGATIVE_INFINITY = "-Infinity"

// ---- Decoding ----

/**
 * Parses [encoded] without needing to know its type, then checks the shape it found
 * against [spec]. Returns null for malformed input, an unknown tag, a length that does
 * not line up, trailing junk, a payload nested deeper than one level, or a shape
 * mismatch. Never throws.
 */
internal fun decodeValue(encoded: String, spec: TypeSpec): Any? {
    val parsed = parseValue(encoded, 0, encoded.length, depth = 0) ?: return null
    if (parsed.end != encoded.length) return null
    return materialise(parsed, spec)
}

/**
 * Decodes a stored override without a spec, for the page's benefit — the shape comes
 * entirely from the tags. Object arrays come back as `Array<Any?>`, since their element
 * type lives in the spec and not on the wire, so this result is for display, not for
 * handing to generated code.
 */
internal fun decodeAny(encoded: String): Any? {
    val parsed = parseValue(encoded, 0, encoded.length, depth = 0) ?: return null
    if (parsed.end != encoded.length) return null
    return infer(parsed)
}

/** One value read off the wire: what tag it carried and where its frame ended. */
private class Parsed(
    val tag: String,
    val end: Int,
    val scalar: Any?,
    val elements: List<Parsed>?,
)

private fun parseValue(encoded: String, from: Int, limit: Int, depth: Int): Parsed? {
    val tagEnd = encoded.indexOf(':', from)
    if (tagEnd <= from || tagEnd >= limit) return null
    val lengthEnd = encoded.indexOf(':', tagEnd + 1)
    if (lengthEnd <= tagEnd + 1 || lengthEnd >= limit) return null

    val length = parseLength(encoded, tagEnd + 1, lengthEnd) ?: return null
    val payloadStart = lengthEnd + 1
    val payloadEnd = payloadStart + length
    if (payloadEnd > limit) return null

    val tag = encoded.substring(from, tagEnd)
    if (!isContainerTag(tag)) {
        if (arityOf(tag) != 0) return null
        val scalar = decodeScalar(tag, encoded.substring(payloadStart, payloadEnd)) ?: return null
        return Parsed(tag, payloadEnd, scalar, elements = null)
    }

    // One nesting level: a container is only ever the whole value, never an element.
    if (depth > 0) return null

    val required = impliedElementTag(tag)
    val elements = mutableListOf<Parsed>()
    var at = payloadStart
    while (at < payloadEnd) {
        val element = parseValue(encoded, at, payloadEnd, depth + 1) ?: return null
        if (required != null && element.tag != required) return null
        elements += element
        at = element.end
    }
    val arity = fixedArity(tag)
    if (arity >= 0 && elements.size != arity) return null
    return Parsed(tag, payloadEnd, scalar = null, elements = elements)
}

/**
 * A length is decimal digits and nothing else, so a sign, a space or a stray character
 * is rejected rather than being read as a number. Nine digits at most, which keeps the
 * accumulation clear of overflow and is longer than any payload a preferences file can
 * hold.
 */
private fun parseLength(encoded: String, from: Int, to: Int): Int? {
    if (to <= from || to - from > 9) return null
    var length = 0
    for (i in from until to) {
        val digit = encoded[i]
        if (digit < '0' || digit > '9') return null
        length = length * 10 + (digit - '0')
    }
    return length
}

private fun decodeScalar(tag: String, payload: String): Any? = when (tag) {
    TAG_INT -> payload.toIntOrNull()
    TAG_LONG -> payload.toLongOrNull()
    TAG_SHORT -> payload.toShortOrNull()
    TAG_BYTE -> payload.toByteOrNull()
    TAG_FLOAT -> decodeFloatPayload(payload)
    TAG_DOUBLE -> decodeDoublePayload(payload)
    TAG_BOOLEAN -> when (payload) {
        "true" -> true
        "false" -> false
        else -> null
    }
    // A Kotlin Char is one UTF-16 unit, so a payload of any other length is not one.
    TAG_CHAR -> if (payload.length == 1) payload[0] else null
    TAG_STRING -> payload
    // The constant name. Resolving it needs the enum's entry table, which the registry
    // has and the codec does not.
    TAG_ENUM -> payload
    else -> null
}

/**
 * Only the three specials the encoder writes are accepted by name, and a finite payload
 * must stay finite: a payload that overflows to infinity is corrupt, not a value, and
 * platforms disagree about whether it parses at all.
 */
private fun decodeFloatPayload(payload: String): Float? = when (payload) {
    NOT_A_NUMBER -> Float.NaN
    POSITIVE_INFINITY -> Float.POSITIVE_INFINITY
    NEGATIVE_INFINITY -> Float.NEGATIVE_INFINITY
    else -> payload.toFloatOrNull()?.takeIf { it.isFinite() }
}

private fun decodeDoublePayload(payload: String): Double? = when (payload) {
    NOT_A_NUMBER -> Double.NaN
    POSITIVE_INFINITY -> Double.POSITIVE_INFINITY
    NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY
    else -> payload.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun materialise(parsed: Parsed, spec: TypeSpec): Any? {
    if (parsed.tag != spec.tag) return null
    val elements = parsed.elements ?: return parsed.scalar

    val elementSpecs = elementSpecsOf(spec, elements.size) ?: return null
    val values = ArrayList<Any?>(elements.size)
    for (i in elements.indices) {
        val element = elements[i]
        // A container in element position is a level too deep; the parser rejects it,
        // and this is the second lock on the same door.
        if (element.elements != null || element.tag != elementSpecs[i].tag) return null
        values += element.scalar
    }
    return buildContainer(spec, values)
}

private fun elementSpecsOf(spec: TypeSpec, count: Int): List<TypeSpec>? {
    impliedElementTag(spec.tag)?.let { implied ->
        return List(count) { scalarSpec(implied) }
    }
    return when (spec.tag) {
        TAG_LIST, TAG_SET, TAG_ARRAY -> List(count) { spec.arguments[0] }
        TAG_PAIR, TAG_TRIPLE -> if (spec.arguments.size == count) spec.arguments else null
        else -> null
    }
}

private fun buildContainer(spec: TypeSpec, values: List<Any?>): Any? = when (spec.tag) {
    TAG_LIST -> values.toList()
    TAG_SET -> values.toSet()
    TAG_ARRAY -> objectArrayOf(spec.arguments[0].tag, values)
    TAG_PAIR -> Pair(values[0], values[1])
    TAG_TRIPLE -> Triple(values[0], values[1], values[2])
    else -> primitiveArrayOf(spec.tag, values)
}

/**
 * The element type of an object array comes from the spec, never from the payload: an
 * empty `arr<str>` has no element to infer from, and an `Array<Any?>` handed to code
 * that casts it to `Array<String>` fails on the JVM.
 */
private fun objectArrayOf(elementTag: String, values: List<Any?>): Any? = when (elementTag) {
    TAG_INT -> Array(values.size) { values[it] as Int }
    TAG_LONG -> Array(values.size) { values[it] as Long }
    TAG_SHORT -> Array(values.size) { values[it] as Short }
    TAG_BYTE -> Array(values.size) { values[it] as Byte }
    TAG_FLOAT -> Array(values.size) { values[it] as Float }
    TAG_DOUBLE -> Array(values.size) { values[it] as Double }
    TAG_BOOLEAN -> Array(values.size) { values[it] as Boolean }
    TAG_CHAR -> Array(values.size) { values[it] as Char }
    TAG_STRING -> Array(values.size) { values[it] as String }
    else -> null
}

// The parser has already checked every element's tag against the array's implied one,
// so these casts cannot fail.
private fun primitiveArrayOf(tag: String, values: List<Any?>): Any? = when (tag) {
    TAG_INT_ARRAY -> IntArray(values.size) { values[it] as Int }
    TAG_LONG_ARRAY -> LongArray(values.size) { values[it] as Long }
    TAG_SHORT_ARRAY -> ShortArray(values.size) { values[it] as Short }
    TAG_BYTE_ARRAY -> ByteArray(values.size) { values[it] as Byte }
    TAG_FLOAT_ARRAY -> FloatArray(values.size) { values[it] as Float }
    TAG_DOUBLE_ARRAY -> DoubleArray(values.size) { values[it] as Double }
    TAG_BOOLEAN_ARRAY -> BooleanArray(values.size) { values[it] as Boolean }
    TAG_CHAR_ARRAY -> CharArray(values.size) { values[it] as Char }
    else -> null
}

private fun infer(parsed: Parsed): Any? {
    val elements = parsed.elements ?: return parsed.scalar
    if (elements.any { it.elements != null }) return null
    val values = elements.map { it.scalar }
    return when (parsed.tag) {
        TAG_LIST -> values
        TAG_SET -> values.toSet()
        TAG_ARRAY -> values.toTypedArray()
        TAG_PAIR -> Pair(values[0], values[1])
        TAG_TRIPLE -> Triple(values[0], values[1], values[2])
        else -> primitiveArrayOf(parsed.tag, values)
    }
}

// ---- Scalar fast paths ----
//
// The resolvers generated code calls take and return primitives and must not pay for
// spec parsing, so each reads its own frame directly.

/** The payload of [encoded] when it is exactly one well-framed [tag] frame. */
private fun payloadOf(encoded: String, tag: String): String? {
    if (!encoded.startsWith(tag)) return null
    var at = tag.length
    if (at >= encoded.length || encoded[at] != ':') return null
    at++
    val lengthEnd = encoded.indexOf(':', at)
    if (lengthEnd < 0) return null
    val length = parseLength(encoded, at, lengthEnd) ?: return null
    val payloadStart = lengthEnd + 1
    // Anything but an exact fit is a truncated payload, an overrunning length or junk
    // after the frame.
    if (payloadStart + length != encoded.length) return null
    return encoded.substring(payloadStart)
}

internal fun encodeInt(value: Int): String = frame(TAG_INT, value.toString())

internal fun decodeInt(encoded: String): Int? = payloadOf(encoded, TAG_INT)?.toIntOrNull()

internal fun decodeLong(encoded: String): Long? = payloadOf(encoded, TAG_LONG)?.toLongOrNull()

internal fun decodeShort(encoded: String): Short? = payloadOf(encoded, TAG_SHORT)?.toShortOrNull()

internal fun decodeByte(encoded: String): Byte? = payloadOf(encoded, TAG_BYTE)?.toByteOrNull()

internal fun decodeFloat(encoded: String): Float? =
    payloadOf(encoded, TAG_FLOAT)?.let(::decodeFloatPayload)

internal fun decodeDouble(encoded: String): Double? =
    payloadOf(encoded, TAG_DOUBLE)?.let(::decodeDoublePayload)

internal fun decodeBoolean(encoded: String): Boolean? = when (payloadOf(encoded, TAG_BOOLEAN)) {
    "true" -> true
    "false" -> false
    else -> null
}

internal fun decodeChar(encoded: String): Char? =
    payloadOf(encoded, TAG_CHAR)?.takeIf { it.length == 1 }?.get(0)

internal fun decodeString(encoded: String): String? = payloadOf(encoded, TAG_STRING)

/** The stored constant name. The registry maps it through the enum's entry table. */
internal fun decodeEnumName(encoded: String): String? = payloadOf(encoded, TAG_ENUM)

// Specs the codec itself needs. Cheap to hold, and it keeps the primitive-array paths
// from parsing a literal to learn what they already know.
private fun scalarSpec(tag: String): TypeSpec = when (tag) {
    TAG_INT -> INT_SPEC
    TAG_LONG -> LONG_SPEC
    TAG_SHORT -> SHORT_SPEC
    TAG_BYTE -> BYTE_SPEC
    TAG_FLOAT -> FLOAT_SPEC
    TAG_DOUBLE -> DOUBLE_SPEC
    TAG_BOOLEAN -> BOOLEAN_SPEC
    TAG_CHAR -> CHAR_SPEC
    else -> TypeSpec(tag, emptyList())
}

private val INT_SPEC = TypeSpec(TAG_INT, emptyList())
private val LONG_SPEC = TypeSpec(TAG_LONG, emptyList())
private val SHORT_SPEC = TypeSpec(TAG_SHORT, emptyList())
private val BYTE_SPEC = TypeSpec(TAG_BYTE, emptyList())
private val FLOAT_SPEC = TypeSpec(TAG_FLOAT, emptyList())
private val DOUBLE_SPEC = TypeSpec(TAG_DOUBLE, emptyList())
private val BOOLEAN_SPEC = TypeSpec(TAG_BOOLEAN, emptyList())
private val CHAR_SPEC = TypeSpec(TAG_CHAR, emptyList())
