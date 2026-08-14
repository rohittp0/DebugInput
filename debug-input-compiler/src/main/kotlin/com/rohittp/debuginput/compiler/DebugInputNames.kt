package com.rohittp.debuginput.compiler

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Names in `debug-input-runtime` that the plugin resolves against. */
internal object DebugInputNames {

    val RUNTIME_PACKAGE: FqName = FqName("com.rohittp.debuginput")

    val DEBUG_INPUT_ANNOTATION: ClassId = ClassId(RUNTIME_PACKAGE, Name.identifier("DebugInput"))

    val REGISTRY: ClassId = ClassId(RUNTIME_PACKAGE, Name.identifier("DebugInputRegistry"))

    val DESCRIPTOR: ClassId = ClassId(RUNTIME_PACKAGE, Name.identifier("DebugInputDescriptor"))

    /**
     * Fixed for every module, so a consumer never has to name a module-mangled symbol and
     * a module rename cannot break hand-written source.
     * See docs/adr/0006-linkage-by-call-site-rewriting.md.
     */
    val GENERATED_PACKAGE: FqName = FqName("com.rohittp.debuginput.generated")

    val PAGE: CallableId = CallableId(
        FqName("com.rohittp.debuginput.compose"),
        Name.identifier("DebugInputsPage"),
    )

    /** The page parameter the plugin fills in; matched by name, not position. */
    const val PAGE_DESCRIPTORS_PARAMETER: String = "descriptors"

    val MUTABLE_LIST_OF: CallableId = CallableId(
        FqName("kotlin.collections"),
        Name.identifier("mutableListOf"),
    )
}

/**
 * `:sample:domain` → `sample_domain`, `:app` → `app`, the root project → `root`. The
 * descriptor function name has to be a legal Kotlin identifier and unique per module.
 */
internal fun sanitizeModule(module: String): String {
    val trimmed = module.trim().trim(':')
    if (trimmed.isEmpty()) return "root"
    return trimmed.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
}

internal fun descriptorFunctionName(module: String): String = "descriptors_${sanitizeModule(module)}"

internal fun descriptorFunctionFqName(module: String): String =
    "${DebugInputNames.GENERATED_PACKAGE.asString()}.${descriptorFunctionName(module)}"
