package com.rohittp.debuginput.compiler

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * A property's type reduced to the three things the plugin needs to know about it. The
 * frontend builds one of these out of a `ConeKotlinType` and the backend out of an `IrType`,
 * so that [classifyDebugInputType] — the rules — exists once rather than twice.
 *
 * [mapLike] is computed by whichever side is asking. The frontend walks supertypes, because it
 * is the gate and has to catch every shape of `Map`; the backend only ever sees types the
 * frontend already accepted.
 */
internal class DebugInputTypeShape(
    val classId: ClassId?,
    val isEnum: Boolean,
    val isNullable: Boolean,
    val mapLike: Boolean,
    val arguments: List<DebugInputTypeShape?>,
) {
    /**
     * Fully qualified, type arguments included — `kotlin.collections.List<kotlin.String>`. Built
     * here rather than taken from a renderer so that diagnostics read the same on every platform
     * and stay stable enough to assert on.
     */
    val rendered: String
        get() {
            val head = classId?.asFqNameString() ?: "?"
            val tail = if (arguments.isEmpty()) {
                ""
            } else {
                arguments.joinToString(", ", "<", ">") { it?.rendered ?: "*" }
            }
            return head + tail + if (isNullable) "?" else ""
        }

    /** What the page shows in its "no renderer registered" row, and M5 looks a renderer up by. */
    val typeKey: String get() = classId?.asFqNameString() ?: rendered
}

/** How a supported input is resolved at runtime. */
internal sealed class DebugInputResolution {

    /** An unboxed fast path: `resolveInt`, `resolveString`, … No spec is parsed at runtime. */
    class Scalar(val resolverName: String) : DebugInputResolution()

    /** `resolveEnum(id, default, entries)`. The entry table is passed because Native has no reflection. */
    object EnumConstant : DebugInputResolution()

    /** `resolveComposite(id, default, spec)`, whose result the call site casts. */
    object Composite : DebugInputResolution()
}

internal sealed class DebugInputTypeClassification {

    class Supported(
        val spec: String,
        val resolution: DebugInputResolution,
    ) : DebugInputTypeClassification()

    class Rejected(val reason: DebugInputTypeRejection) : DebugInputTypeClassification()
}

internal enum class DebugInputTypeRejection {
    /** No codec tag covers this type at all. */
    UNSUPPORTED,

    /** A container holding another container — one nesting level is the whole design. */
    NESTED,

    /** `Map` of any shape. */
    MAP,

    /** An enum in element position, which no spec string can carry an entry table for. */
    ENUM_IN_CONTAINER,
}

/**
 * The single source of truth for what `@DebugInput` accepts and what spec literal each accepted
 * type gets. The grammar has to agree with `parseTypeSpec` in the runtime's `Codec.kt`, because
 * the page dispatches its renderers on the string this produces.
 */
internal fun classifyDebugInputType(shape: DebugInputTypeShape): DebugInputTypeClassification {
    // Nothing in the codec has a null form: every payload decodes to a value or to nothing at all.
    if (shape.isNullable) return rejected(DebugInputTypeRejection.UNSUPPORTED)
    val classId = shape.classId ?: return rejected(DebugInputTypeRejection.UNSUPPORTED)
    if (shape.mapLike || classId in MAP_IDS) return rejected(DebugInputTypeRejection.MAP)

    SCALAR_SPECS[classId]?.let { spec ->
        return DebugInputTypeClassification.Supported(spec, DebugInputResolution.Scalar(RESOLVERS.getValue(spec)))
    }
    if (shape.isEnum) {
        return DebugInputTypeClassification.Supported(TAG_ENUM, DebugInputResolution.EnumConstant)
    }
    PRIMITIVE_ARRAY_SPECS[classId]?.let { spec ->
        return DebugInputTypeClassification.Supported(spec, DebugInputResolution.Composite)
    }

    val containerTag = CONTAINER_TAGS[classId] ?: return rejected(DebugInputTypeRejection.UNSUPPORTED)
    val arity = CONTAINER_ARITY.getValue(containerTag)
    if (shape.arguments.size != arity) return rejected(DebugInputTypeRejection.UNSUPPORTED)

    val elementSpecs = shape.arguments.map { argument ->
        when (val element = classifyElement(argument)) {
            is DebugInputTypeClassification.Rejected -> return element
            is DebugInputTypeClassification.Supported -> element.spec
        }
    }
    return DebugInputTypeClassification.Supported(
        spec = elementSpecs.joinToString(",", "$containerTag<", ">"),
        resolution = DebugInputResolution.Composite,
    )
}

/** A container element must be a scalar, and must not be an enum. */
private fun classifyElement(shape: DebugInputTypeShape?): DebugInputTypeClassification {
    if (shape == null) return rejected(DebugInputTypeRejection.UNSUPPORTED)
    if (shape.isNullable) return rejected(DebugInputTypeRejection.UNSUPPORTED)
    val classId = shape.classId ?: return rejected(DebugInputTypeRejection.UNSUPPORTED)
    if (shape.mapLike || classId in MAP_IDS) return rejected(DebugInputTypeRejection.MAP)
    if (shape.isEnum) return rejected(DebugInputTypeRejection.ENUM_IN_CONTAINER)
    if (classId in PRIMITIVE_ARRAY_SPECS || classId in CONTAINER_TAGS) {
        return rejected(DebugInputTypeRejection.NESTED)
    }
    val spec = SCALAR_SPECS[classId] ?: return rejected(DebugInputTypeRejection.UNSUPPORTED)
    return DebugInputTypeClassification.Supported(spec, DebugInputResolution.Scalar(RESOLVERS.getValue(spec)))
}

private fun rejected(reason: DebugInputTypeRejection) = DebugInputTypeClassification.Rejected(reason)

// ---- The table. Tags are ADR-0008's; see parseTypeSpec in the runtime's Codec.kt. ----

private const val TAG_ENUM = "enm"

private val KOTLIN = FqName("kotlin")
private val KOTLIN_COLLECTIONS = FqName("kotlin.collections")

private fun kotlin(name: String) = ClassId(KOTLIN, Name.identifier(name))

private fun collections(name: String) = ClassId(KOTLIN_COLLECTIONS, Name.identifier(name))

private val SCALAR_SPECS: Map<ClassId, String> = mapOf(
    kotlin("Int") to "int",
    kotlin("Long") to "lng",
    kotlin("Short") to "sht",
    kotlin("Byte") to "byt",
    kotlin("Float") to "flt",
    kotlin("Double") to "dbl",
    kotlin("Boolean") to "bln",
    kotlin("Char") to "chr",
    kotlin("String") to "str",
)

private val RESOLVERS: Map<String, String> = mapOf(
    "int" to "resolveInt",
    "lng" to "resolveLong",
    "sht" to "resolveShort",
    "byt" to "resolveByte",
    "flt" to "resolveFloat",
    "dbl" to "resolveDouble",
    "bln" to "resolveBoolean",
    "chr" to "resolveChar",
    "str" to "resolveString",
)

private val PRIMITIVE_ARRAY_SPECS: Map<ClassId, String> = mapOf(
    kotlin("IntArray") to "iarr",
    kotlin("LongArray") to "larr",
    kotlin("ShortArray") to "sarr",
    kotlin("ByteArray") to "barr",
    kotlin("FloatArray") to "farr",
    kotlin("DoubleArray") to "darr",
    kotlin("BooleanArray") to "zarr",
    kotlin("CharArray") to "carr",
)

/**
 * `MutableList` and friends are deliberately absent: the codec rebuilds a container as the
 * read-only kind, and handing that back through a `MutableList` getter would be a lie the cast
 * cannot catch.
 */
private val CONTAINER_TAGS: Map<ClassId, String> = mapOf(
    collections("List") to "lst",
    collections("Set") to "set",
    kotlin("Array") to "arr",
    kotlin("Pair") to "pair",
    kotlin("Triple") to "trip",
)

private val CONTAINER_ARITY: Map<String, Int> = mapOf(
    "lst" to 1,
    "set" to 1,
    "arr" to 1,
    "pair" to 2,
    "trip" to 3,
)

/**
 * The declared map types. The frontend also walks supertypes, so a `HashMap` or a consumer's own
 * `Map` implementation is caught there; this set is what the backend and the element check use.
 */
private val MAP_IDS: Set<ClassId> = setOf(
    collections("Map"),
    collections("MutableMap"),
    collections("HashMap"),
    collections("LinkedHashMap"),
    ClassId(FqName("java.util"), Name.identifier("Map")),
    ClassId(FqName("java.util"), Name.identifier("HashMap")),
    ClassId(FqName("java.util"), Name.identifier("LinkedHashMap")),
)

internal val MAP_CLASS_ID: ClassId = collections("Map")
