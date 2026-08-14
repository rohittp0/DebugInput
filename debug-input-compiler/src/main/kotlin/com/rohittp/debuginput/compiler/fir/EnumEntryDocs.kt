// Reading the declaration list of a fully resolved class from the backend, where the lazy
// resolution the opt-in guards against has already happened.
@file:OptIn(DirectDeclarationsAccess::class)

package com.rohittp.debuginput.compiler.fir

import com.rohittp.debuginput.compiler.DebugInputNames
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.Name

/**
 * The `docs` string for each constant of an enum, by constant name.
 *
 * Precedence:
 * 1. an explicit `@DebugInput(docs = "…")` on the constant. Verified against 2.3.21: the
 *    annotation's existing `PROPERTY` and `CLASS` targets already allow it on an enum entry, and it
 *    is visible on the `FirEnumEntry` under both parsers;
 * 2. the constant's KDoc, normalised for a phone-sized popup by [kdocToDocs];
 * 3. empty.
 *
 * KDoc rather than a re-typed annotation argument because a file like `MagicNumbers` already
 * carries the documentation — twenty lines of measurements and cross-platform history on some
 * constants — and a second copy in an annotation would drift from the first.
 */
internal fun FirClass.enumEntryDocs(): Map<String, String> =
    declarations.filterIsInstance<FirEnumEntry>().associate { entry ->
        entry.name.asString() to docsOf(entry.annotations, entry.source)
    }

/** Documentation for an ordinary annotated property, explicit annotation text first. */
internal fun FirProperty.declarationDocs(): String = docsOf(annotations, source)

/** Documentation for an annotated enum class, used as its page's section description. */
internal fun FirClass.declarationDocs(): String = docsOf(annotations, source)

private fun docsOf(annotations: List<FirAnnotation>, source: KtSourceElement?): String {
    annotations.explicitDocs()?.let { return it }
    return source?.rawKDoc()?.let(::kdocToDocs).orEmpty()
}

private fun List<FirAnnotation>.explicitDocs(): String? {
    val annotation = firstOrNull {
        it.annotationTypeRef.coneType.classId == DebugInputNames.DEBUG_INPUT_ANNOTATION
    } ?: return null
    return annotation.docsArgument()
}

private fun FirAnnotation.docsArgument(): String? {
    val argument = argumentMapping.mapping[Name.identifier("docs")] ?: return null
    return (argument as? FirLiteralExpression)?.value as? String
}
