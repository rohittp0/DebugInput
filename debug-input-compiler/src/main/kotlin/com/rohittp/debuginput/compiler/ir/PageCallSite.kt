// See DebugInputIrGenerationExtension for why the opt-in is safe here.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.rohittp.debuginput.compiler.ir

import com.rohittp.debuginput.compiler.DebugInputNames
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

/**
 * Turns `DebugInputsPage()` into `DebugInputsPage(descriptors = descriptors_app())`.
 *
 * The rewrite happens here rather than by making the consumer name the generated symbol,
 * because a module-mangled name in hand-written source breaks the moment somebody renames
 * the module. See docs/adr/0006-linkage-by-call-site-rewriting.md.
 *
 * A call that passes `descriptors` explicitly is left alone: somebody who built the list
 * themselves means it.
 */
internal fun rewritePageCallSites(
    moduleFragment: IrModuleFragment,
    pluginContext: IrPluginContext,
    descriptorFunction: IrSimpleFunction,
): Int {
    val rewriter = PageCallSiteRewriter(pluginContext, descriptorFunction)
    moduleFragment.transform(rewriter, null)
    return rewriter.rewritten
}

private class PageCallSiteRewriter(
    private val pluginContext: IrPluginContext,
    private val descriptorFunction: IrSimpleFunction,
) : IrElementTransformerVoid() {

    var rewritten: Int = 0
        private set

    override fun visitCall(expression: IrCall): IrExpression {
        val result = super.visitCall(expression)
        if (result !is IrCall) return result

        val callee = result.symbol.owner
        if (!callee.isDebugInputsPage()) return result

        val index = callee.parameters.indexOfFirst {
            it.name.asString() == DebugInputNames.PAGE_DESCRIPTORS_PARAMETER
        }
        if (index < 0) return result
        // A default argument reaches IR as a hole, which is exactly the call the ADR
        // describes; anything else was written on purpose.
        if (result.arguments[index] != null) return result

        // The builder's scope only supplies a parent for temporaries, and this call makes
        // none, so the generated function is as good a scope as the call site's own.
        val builder = DeclarationIrBuilder(pluginContext, descriptorFunction.symbol)
        result.arguments[index] = builder.irCall(descriptorFunction.symbol)
        rewritten++
        return result
    }
}

private fun IrSimpleFunction.isDebugInputsPage(): Boolean {
    if (name != DebugInputNames.PAGE.callableName) return false
    val packageFqName = (parent as? IrPackageFragment)?.packageFqName ?: return false
    return packageFqName == DebugInputNames.PAGE.packageName
}
