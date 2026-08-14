package com.rohittp.debuginput.compiler.ir

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/**
 * The id an input is known by across launches and refactors, per
 * docs/adr/0005-id-derivation-and-dormant-overrides.md.
 *
 * It is the fully qualified name — `com.app.physics.speed`, `com.app.Config.timeout` —
 * except for **private top-level** properties, which additionally carry their file name:
 * `com.app.physics.Physics.kt.speed`. Two files in one package may each declare
 * `private val speed` and compile cleanly, so those are the only declarations Kotlin does
 * not keep unique by qualified name.
 */
internal fun debugInputId(property: IrProperty): String = idSegments(property).joinToString(".")

/**
 * The id of one constant's copy of an enum-class input: the property's own id with the constant
 * inserted before the property name — `com.app.MagicNumbers.INTRO_INFLEXION.values`.
 */
internal fun enumConstantInputId(property: IrProperty, entryName: String): String {
    val segments = idSegments(property)
    segments.add(segments.size - 1, entryName)
    return segments.joinToString(".")
}

private fun idSegments(property: IrProperty): MutableList<String> {
    val segments = ArrayDeque<String>()
    segments.addFirst(property.name.asString())

    var owner = property.parent
    while (owner is IrClass) {
        segments.addFirst(owner.name.asString())
        owner = owner.parent
    }

    val file = owner as? IrFile ?: return segments

    val isTopLevel = property.parent === file
    if (isTopLevel && property.visibility == DescriptorVisibilities.PRIVATE) {
        segments.addFirst(file.baseName)
    }

    val packageFqName = file.packageFqName
    if (!packageFqName.isRoot) segments.addFirst(packageFqName.asString())

    return segments
}

/**
 * The page's inner grouping level: the declaring class, object or enum, or the file's base
 * name without `.kt` for a top-level property.
 */
internal fun debugInputSection(property: IrProperty): String {
    val owner = property.parent
    if (owner is IrClass) return owner.name.asString()
    return (owner as? IrFile)?.baseName?.removeSuffix(".kt") ?: owner.toString()
}

/**
 * Stable identity of the section page produced by the compiler. The visible section title is not
 * enough: two classes or files may legitimately share a simple name without describing one page.
 */
internal fun debugInputSectionPageId(property: IrProperty): String {
    val owner = property.parent
    if (owner is IrClass) {
        val identity = owner.fqNameWhenAvailable?.asString()
            ?: debugInputId(property).substringBeforeLast('.')
        return "class:$identity"
    }

    val file = owner as? IrFile
    val packageName = file?.packageFqName?.asString().orEmpty()
    val fileName = file?.baseName?.removeSuffix(".kt") ?: debugInputSection(property)
    return "file:$packageName/$fileName"
}

/** `Physics.kt` out of whatever absolute path the compiler was handed. */
private val IrFile.baseName: String
    get() = fileEntry.name.substringAfterLast('/').substringAfterLast('\\')
