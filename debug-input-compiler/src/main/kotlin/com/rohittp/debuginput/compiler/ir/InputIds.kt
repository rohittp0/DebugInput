package com.rohittp.debuginput.compiler.ir

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty

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
internal fun debugInputId(property: IrProperty): String {
    val segments = ArrayDeque<String>()
    segments.addFirst(property.name.asString())

    var owner = property.parent
    while (owner is IrClass) {
        segments.addFirst(owner.name.asString())
        owner = owner.parent
    }

    val file = owner as? IrFile ?: return segments.joinToString(".")

    val isTopLevel = property.parent === file
    if (isTopLevel && property.visibility == DescriptorVisibilities.PRIVATE) {
        segments.addFirst(file.baseName)
    }

    val packageFqName = file.packageFqName
    if (!packageFqName.isRoot) segments.addFirst(packageFqName.asString())

    return segments.joinToString(".")
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

/** `Physics.kt` out of whatever absolute path the compiler was handed. */
private val IrFile.baseName: String
    get() = fileEntry.name.substringAfterLast('/').substringAfterLast('\\')
