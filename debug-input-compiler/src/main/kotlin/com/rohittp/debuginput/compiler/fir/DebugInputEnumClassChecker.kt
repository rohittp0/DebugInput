// A checker sees a fully resolved class from the compiler's own pipeline, where reading the
// declaration list directly is exactly what the opt-in warns is unsafe only under the IDE's lazy
// resolution.
@file:OptIn(DirectDeclarationsAccess::class)

package com.rohittp.debuginput.compiler.fir

import com.rohittp.debuginput.compiler.DebugInputNames
import com.rohittp.debuginput.compiler.DebugInputTypeClassification
import com.rohittp.debuginput.compiler.DebugInputTypeRejection
import com.rohittp.debuginput.compiler.classifyDebugInputType
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType

/**
 * `@DebugInput` on a class.
 *
 * On an enum class it makes every constructor `val` of every constant an input of its own
 * (design-note §1). On anything else it is rejected: the annotation has `CLASS` in its `@Target`,
 * so it compiles, and before this checker existed it compiled to nothing at all — a silent no-op
 * being worse than a rejection is the whole reason this exists.
 *
 * An unsupported or `var` constructor property is rejected on its own without failing the enum:
 * one bad `val` among five should not cost the other four their rows.
 */
internal object DebugInputEnumClassChecker : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        if (!declaration.hasDebugInputAnnotation()) return
        val source = declaration.source ?: return
        val name = declaration.name.asString()

        if (declaration.classKind != ClassKind.ENUM_CLASS) {
            reporter.reportOn(source, DebugInputErrors.NOT_AN_ENUM_CLASS, name)
            return
        }

        val constructorProperties = declaration.constructorProperties()
        var supported = 0
        for (property in constructorProperties) {
            val propertyName = property.name.asString()
            if (property.isVar) {
                reporter.reportOn(source, DebugInputErrors.ENUM_CONSTRUCTOR_VAR, "$name.$propertyName", name)
                continue
            }
            val shape = property.returnTypeRef.coneType.toDebugInputShape(context.session)
            when (classifyDebugInputType(shape)) {
                is DebugInputTypeClassification.Supported -> supported++
                is DebugInputTypeClassification.Rejected -> reporter.reportOn(
                    source,
                    DebugInputErrors.ENUM_CONSTRUCTOR_TYPE,
                    "$name.$propertyName",
                    shape.rendered,
                )
            }
        }

        if (supported == 0) {
            reporter.reportOn(source, DebugInputErrors.ENUM_WITHOUT_INPUTS, name)
        }
    }

    /**
     * The properties declared by the primary constructor, in declaration order. Matched by name
     * against the constructor's parameters, because that is the definition design-note §1 gives —
     * a body-declared `val` of an enum is one value shared by every constant, not one per constant.
     */
    private fun FirRegularClass.constructorProperties(): List<FirProperty> {
        val primary = declarations.filterIsInstance<FirConstructor>().firstOrNull { it.isPrimary }
            ?: return emptyList()
        val parameterNames = primary.valueParameters.mapTo(mutableSetOf()) { it.name }
        val properties = declarations.filterIsInstance<FirProperty>().associateBy { it.name }
        return primary.valueParameters.mapNotNull { properties[it.name] }
            .filter { it.name in parameterNames }
    }

    private fun FirRegularClass.hasDebugInputAnnotation(): Boolean =
        annotations.any {
            it.annotationTypeRef.coneType.classId == DebugInputNames.DEBUG_INPUT_ANNOTATION
        }
}
