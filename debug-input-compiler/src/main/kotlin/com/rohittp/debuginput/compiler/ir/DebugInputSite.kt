// See DebugInputIrGenerationExtension for why the opt-in is safe here.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.rohittp.debuginput.compiler.ir

import com.rohittp.debuginput.compiler.DebugInputNames
import com.rohittp.debuginput.compiler.DebugInputResolution
import com.rohittp.debuginput.compiler.DebugInputTypeClassification
import com.rohittp.debuginput.compiler.classifyDebugInputType
import com.rohittp.debuginput.compiler.fir.enumEntryDocs
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.hasAnnotation

/**
 * One debug input as the backend sees it: everything the getter rewrite, the descriptor and the
 * descriptor manifest need, derived once.
 *
 * An **enum-class input** produces one site per constant, all sharing the same [property] — there is
 * a single `values` getter on the enum, not one per constant. That is what [enumEntry] marks, and
 * what forces the getter to pick its id from the receiver rather than from a baked-in literal.
 */
internal class DebugInputSite(
    val property: IrProperty,
    val file: IrFile,
    val id: String,
    val displayName: String,
    val section: String,
    val docs: String,
    val typeKey: String,
    /** The ADR-0008 codec spec literal for this input's type, e.g. `int`, `lst<str>`, `darr`. */
    val spec: String,
    val resolution: DebugInputResolution,
    /** The constant this input belongs to, or null for an ordinary property input. */
    val enumEntry: IrEnumEntry? = null,
)

/**
 * Every input declared in [file]: annotated properties, and every constructor `val` of every
 * constant of an annotated enum class.
 *
 * A property whose type the shared rules reject is skipped rather than guessed at: the frontend has
 * already failed the compilation for it, and inventing a resolver for a type with no codec would
 * turn a clear diagnostic into a backend crash.
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

            is IrClass -> {
                if (declaration.kind == ClassKind.ENUM_CLASS &&
                    declaration.hasAnnotation(DebugInputNames.DEBUG_INPUT_ANNOTATION)
                ) {
                    into += declaration.enumClassSites(file)
                }
                collectSites(declaration.declarations, file, into)
            }

            else -> Unit
        }
    }
}

/**
 * One site per (constant, constructor `val`) pair.
 *
 * `displayName` is the constant alone when the enum has exactly one instrumentable constructor
 * `val`, and `CONSTANT.property` otherwise. `MagicNumbers` has ~100 constants and one `values`
 * property; repeating `.values` down every row of the page is noise, and the property name carries
 * no information the section header does not already give.
 */
private fun IrClass.enumClassSites(file: IrFile): List<DebugInputSite> {
    val entries = declarations.filterIsInstance<IrEnumEntry>()
    if (entries.isEmpty()) return emptyList()

    val properties = instrumentableConstructorProperties()
    if (properties.isEmpty()) return emptyList()
    val qualifyWithProperty = properties.size > 1

    val docsByEntry = resolveEnumEntryDocs()
    val section = name.asString()

    return properties.flatMap { (property, supported) ->
        entries.map { entry ->
            val entryName = entry.name.asString()
            DebugInputSite(
                property = property,
                file = file,
                id = enumConstantInputId(property, entryName),
                displayName = if (qualifyWithProperty) {
                    "$entryName.${property.name.asString()}"
                } else {
                    entryName
                },
                section = section,
                docs = docsByEntry[entryName].orEmpty(),
                typeKey = supported.typeKey,
                spec = supported.spec,
                resolution = supported.resolution,
                enumEntry = entry,
            )
        }
    }
}

/** Each constructor `val` whose type has a codec, paired with what the rules made of it. */
private fun IrClass.instrumentableConstructorProperties(): List<Pair<IrProperty, SupportedType>> {
    val primary = declarations.filterIsInstance<IrConstructor>().firstOrNull { it.isPrimary }
        ?: return emptyList()
    // Filtered by type, not by name: the synthetic `values()` function of an enum shares its name
    // with a `vararg val values` property, and only one of the two is an IrProperty.
    val properties = declarations.filterIsInstance<IrProperty>().associateBy { it.name }

    return primary.parameters.mapNotNull { parameter ->
        val property = properties[parameter.name] ?: return@mapNotNull null
        if (property.isVar) return@mapNotNull null
        property.supportedType()?.let { property to it }
    }
}

private class SupportedType(val spec: String, val resolution: DebugInputResolution, val typeKey: String)

private fun IrProperty.supportedType(): SupportedType? {
    val type = getter?.returnType ?: backingField?.type ?: return null
    val shape = type.toDebugInputShape()
    val supported = classifyDebugInputType(shape) as? DebugInputTypeClassification.Supported
        ?: return null
    return SupportedType(supported.spec, supported.resolution, shape.typeKey)
}

/**
 * The KDoc and explicit annotations of the enum's constants, reached through the frontend class the
 * backend still holds on to. IR itself carries no comments, and the alternative — re-reading the
 * source file and scanning for a doc comment by offset — would reimplement the lexer.
 */
private fun IrClass.resolveEnumEntryDocs(): Map<String, String> =
    (metadata as? FirMetadataSource.Class)?.fir?.enumEntryDocs().orEmpty()

private fun IrProperty.toSiteOrNull(file: IrFile): DebugInputSite? {
    val supported = supportedType() ?: return null
    return DebugInputSite(
        property = this,
        file = file,
        id = debugInputId(this),
        displayName = name.asString(),
        section = debugInputSection(this),
        docs = debugInputDocs(),
        typeKey = supported.typeKey,
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
