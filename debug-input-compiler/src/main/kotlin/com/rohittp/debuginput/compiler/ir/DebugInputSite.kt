// See DebugInputIrGenerationExtension for why the opt-in is safe here.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.rohittp.debuginput.compiler.ir

import com.rohittp.debuginput.compiler.DebugInputNames
import com.rohittp.debuginput.compiler.DebugInputResolution
import com.rohittp.debuginput.compiler.DebugInputTypeClassification
import com.rohittp.debuginput.compiler.classifyDebugInputType
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.hasAnnotation

/**
 * One debug input as the backend sees it: everything the getter rewrite, the descriptor and
 * the descriptor manifest need, derived once.
 */
internal class DebugInputSite(
    val property: IrProperty,
    val file: IrFile,
    val id: String,
    val section: String,
    val docs: String,
    val typeKey: String,
    /** The ADR-0008 codec spec literal for this input's type, e.g. `int`, `lst<str>`, `iarr`. */
    val spec: String,
    val resolution: DebugInputResolution,
) {
    val displayName: String get() = property.name.asString()
}

/**
 * Every `@DebugInput` property in [file], including ones nested inside classes.
 *
 * A property whose type the shared rules reject is skipped rather than guessed at: the frontend
 * has already failed the compilation for it, and inventing a resolver for a type with no codec
 * would turn a clear diagnostic into a backend crash.
 */
internal fun debugInputSitesIn(file: IrFile): List<DebugInputSite> =
    buildList { collectSites(file.declarations, file, this) }

private fun collectSites(
    declarations: List<IrDeclaration>,
    file: IrFile,
    into: MutableList<DebugInputSite>,
) {
    for (declaration in declarations) {
        when (declaration) {
            is IrProperty ->
                if (declaration.hasAnnotation(DebugInputNames.DEBUG_INPUT_ANNOTATION)) {
                    declaration.toSiteOrNull(file)?.let { into += it }
                }

            is IrClass -> collectSites(declaration.declarations, file, into)

            else -> Unit
        }
    }
}

private fun IrProperty.toSiteOrNull(file: IrFile): DebugInputSite? {
    val type = getter?.returnType ?: backingField?.type ?: return null
    val shape = type.toDebugInputShape()
    val supported = classifyDebugInputType(shape) as? DebugInputTypeClassification.Supported
        ?: return null

    return DebugInputSite(
        property = this,
        file = file,
        id = debugInputId(this),
        section = debugInputSection(this),
        docs = debugInputDocs(),
        typeKey = shape.typeKey,
        spec = supported.spec,
        resolution = supported.resolution,
    )
}

/**
 * The annotation's `docs` argument. Empty when it was left at its default, which reaches IR
 * as a missing argument rather than as the default expression.
 */
private fun IrProperty.debugInputDocs(): String {
    val annotationFqName = DebugInputNames.DEBUG_INPUT_ANNOTATION.asSingleFqName()
    val annotation = annotations.firstOrNull { it.type.classFqName == annotationFqName } ?: return ""
    val index = annotation.symbol.owner.parameters.indexOfFirst { it.name.asString() == "docs" }
    if (index < 0) return ""
    return (annotation.arguments.getOrNull(index) as? IrConst)?.value as? String ?: ""
}
