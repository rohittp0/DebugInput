package com.rohittp.debuginput.compiler.fir

import com.rohittp.debuginput.compiler.DebugInputNames
import com.rohittp.debuginput.compiler.DebugInputTypeClassification
import com.rohittp.debuginput.compiler.DebugInputTypeRejection
import com.rohittp.debuginput.compiler.classifyDebugInputType
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType

/**
 * Rejects every `@DebugInput` the IR transform could not honour. Registered
 * unconditionally, including when the transform is disabled, so a misuse fails an Android
 * release build as loudly as a debug one.
 */
internal object DebugInputPropertyChecker : FirDeclarationChecker<FirProperty>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (!declaration.hasDebugInputAnnotation()) return

        val source = declaration.source ?: return
        val name = declaration.name.asString()

        when {
            declaration.status.isConst ->
                reporter.reportOn(source, DebugInputErrors.CONST_VAL, name)

            declaration.isVar ->
                reporter.reportOn(source, DebugInputErrors.VAR_PROPERTY, name)

            declaration.delegate != null ->
                reporter.reportOn(source, DebugInputErrors.DELEGATED_PROPERTY, name)

            declaration.isLocal ->
                reporter.reportOn(source, DebugInputErrors.LOCAL_PROPERTY, name)

            declaration.status.isExpect || declaration.status.isActual ->
                reporter.reportOn(source, DebugInputErrors.EXPECT_ACTUAL_PROPERTY, name)

            declaration.getter.isCustom() ->
                reporter.reportOn(source, DebugInputErrors.CUSTOM_GETTER, name)

            else -> checkType(declaration, source, name)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkType(
        declaration: FirProperty,
        source: KtSourceElement,
        name: String,
    ) {
        val shape = declaration.returnTypeRef.coneType.toDebugInputShape(context.session)
        val classification = classifyDebugInputType(shape)
        if (classification is DebugInputTypeClassification.Supported) return

        val reason = (classification as DebugInputTypeClassification.Rejected).reason
        val factory = when (reason) {
            DebugInputTypeRejection.UNSUPPORTED -> DebugInputErrors.UNSUPPORTED_TYPE
            DebugInputTypeRejection.NESTED -> DebugInputErrors.NESTED_TYPE
            DebugInputTypeRejection.MAP -> DebugInputErrors.MAP_TYPE
            DebugInputTypeRejection.ENUM_IN_CONTAINER -> DebugInputErrors.ENUM_IN_CONTAINER
        }
        // The whole property type, not the offending argument: a message about `kotlin.Int` on a
        // `List<List<Int>>` property would send the reader looking in the wrong place.
        reporter.reportOn(source, factory, name, shape.rendered)
    }

    private fun FirProperty.hasDebugInputAnnotation(): Boolean =
        annotations.any { it.annotationTypeRef.coneType.classId == DebugInputNames.DEBUG_INPUT_ANNOTATION }

    /**
     * A getter the user wrote has a body; the one the compiler synthesises for a property
     * with a backing field does not.
     */
    private fun FirPropertyAccessor?.isCustom(): Boolean = this != null && body != null
}
