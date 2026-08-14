// Reading `declarations` and dereferencing a symbol's `owner` are both gated in 2.3.21
// because IR may still be under construction. An IrGenerationExtension runs after fir2ir
// has finished, so the module fragment it is handed is complete.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.rohittp.debuginput.compiler.ir

import com.rohittp.debuginput.compiler.DebugInputNames
import com.rohittp.debuginput.compiler.DebugInputResolution
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObjectValue
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * The whole backend half of the plugin, in the order the pieces depend on each other:
 *
 * 1. every `@DebugInput val` getter is replaced with a call into the registry, chosen by the
 *    property's static type, so a plain read returns the override;
 * 2. one descriptor function is emitted for the module, aggregating its own inputs and its
 *    dependencies';
 * 3. every defaulted `DebugInputsPage()` call is rewritten to pass that function's result;
 * 4. the descriptor manifest is written for the Gradle plugin.
 *
 * Registered only when the transform is enabled, which is every compilation except an
 * Android release one (ADR-0002).
 */
internal class DebugInputIrGenerationExtension(
    private val module: String,
    private val manifestOut: String?,
    private val dependencyDescriptors: List<String>,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        // Snapshot before emitting: the generated file is appended to the module and has no
        // inputs of its own to find.
        val sites = moduleFragment.files.flatMap(::debugInputSitesIn)

        rewriteGetters(sites, pluginContext)

        val descriptorFunction = DescriptorFunctionEmitter(pluginContext, module, dependencyDescriptors)
            .emit(moduleFragment, sites)
        if (descriptorFunction != null) {
            rewritePageCallSites(moduleFragment, pluginContext, descriptorFunction)
        }

        // Written even with no inputs: the Gradle plugin has to tell "instrumented, declared
        // nothing" apart from "never instrumented".
        manifestOut?.let { writeDescriptorManifest(it, module, sites) }
    }

    private fun rewriteGetters(sites: List<DebugInputSite>, pluginContext: IrPluginContext) {
        for ((file, inFile) in sites.groupBy { it.file }) {
            // Resolved per file rather than per module: the finder is scoped to what that
            // file can actually see.
            val finder = pluginContext.finderForSource(file)
            val registry = finder.findClass(DebugInputNames.REGISTRY) ?: continue
            val resolvers = RegistryResolvers(finder)

            // One getter per property, not per input: an enum-class input has as many ids as the
            // enum has constants and exactly one getter to serve them all.
            for ((_, forProperty) in inFile.groupBy { it.property }) {
                rewriteGetter(forProperty, pluginContext, finder, file, registry, resolvers)
            }
        }
    }

    /**
     * The default is read out of the property's **own** backing field rather than a
     * synthesised copy: the field already holds whatever the initializer produced, and the
     * getter is the only thing standing between a read and that field.
     */
    private fun rewriteGetter(
        sites: List<DebugInputSite>,
        pluginContext: IrPluginContext,
        finder: DeclarationFinder,
        file: IrFile,
        registry: IrClassSymbol,
        resolvers: RegistryResolvers,
    ) {
        val site = sites.first()
        val getter = site.property.getter ?: return

        val idTable = if (site.enumEntry == null) {
            null
        } else {
            val enumClass = site.property.parent as? IrClass ?: return
            emitEnumIdTable(
                pluginContext = pluginContext,
                finder = finder,
                file = file,
                enumClass = enumClass,
                property = site.property,
                // Ordinal order, which is the order the constants are declared in and the order
                // `sites` was built in.
                idsByOrdinal = sites.map { it.id },
            ) ?: return
        }

        val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
        val resolved = builder.resolveCall(site, getter, registry, resolvers, idTable) ?: return

        // The JVM backend replaces calls to a DEFAULT_PROPERTY_ACCESSOR with direct field
        // access whenever the call site can reach the field — which is every read inside
        // the declaring file, and the only kind of read a private input ever gets. The
        // origin is what that decision keys on, not the body, so a rewritten getter has to
        // stop claiming to be the default one.
        getter.origin = IrDeclarationOrigin.DEFINED
        getter.body = builder.irBlockBody { +irReturn(resolved) }
    }

    /**
     * Dispatch by static type. A scalar takes an unboxed fast path that never parses a spec; an
     * enum is handed its own table of constants, because Kotlin/Native has no reflection to
     * recover it; everything else goes through the one generic entry point with the spec literal
     * baked in and the result cast back to the property's type.
     */
    private fun IrBuilderWithScope.resolveCall(
        site: DebugInputSite,
        getter: IrSimpleFunction,
        registry: IrClassSymbol,
        resolvers: RegistryResolvers,
        idTable: EnumIdTable?,
    ): IrExpression? {
        val field = site.property.backingField ?: return null
        val receiver = getter.dispatchReceiverParameter
        val default = irGetField(receiver?.let { irGet(it) }, field)
        // An enum-class input picks its id off the receiver; everything else knows it statically.
        val id = when {
            idTable == null -> irString(site.id)
            receiver == null -> return null
            else -> idTable.idOf(this, irGet(receiver))
        }

        return when (val resolution = site.resolution) {
            is DebugInputResolution.Scalar -> {
                val resolver = resolvers.named(resolution.resolverName) ?: return null
                irCall(resolver).apply {
                    arguments[0] = irGetObjectValue(registry.owner.defaultType, registry)
                    arguments[1] = id
                    arguments[2] = default
                }
            }

            DebugInputResolution.EnumConstant -> {
                val resolver = resolvers.named(RESOLVE_ENUM) ?: return null
                val enumClass = getter.returnType.classOrNull?.owner ?: return null
                if (enumClass.kind != ClassKind.ENUM_CLASS) return null
                val values = enumClass.functions
                    .singleOrNull { it.name.asString() == "values" && it.parameters.isEmpty() }
                    ?: return null
                irCall(resolver).apply {
                    typeArguments[0] = getter.returnType
                    arguments[0] = irGetObjectValue(registry.owner.defaultType, registry)
                    arguments[1] = id
                    arguments[2] = default
                    arguments[3] = irCall(values.symbol)
                }
            }

            DebugInputResolution.Composite -> {
                val resolver = resolvers.named(RESOLVE_COMPOSITE) ?: return null
                val call = irCall(resolver).apply {
                    arguments[0] = irGetObjectValue(registry.owner.defaultType, registry)
                    arguments[1] = id
                    arguments[2] = default
                    arguments[3] = irString(site.spec)
                }
                // resolveComposite is typed Any? because one entry point serves every composite.
                // The spec it was given describes this property's type, so the value coming back
                // is that type; nothing else can decode to it.
                IrTypeOperatorCallImpl(
                    startOffset,
                    endOffset,
                    getter.returnType,
                    IrTypeOperator.IMPLICIT_CAST,
                    getter.returnType,
                    call,
                )
            }
        }
    }

    private companion object {
        const val RESOLVE_ENUM = "resolveEnum"
        const val RESOLVE_COMPOSITE = "resolveComposite"
    }
}

/** Registry resolvers by name, looked up once per file. */
private class RegistryResolvers(private val finder: DeclarationFinder) {

    private val cache = mutableMapOf<String, IrSimpleFunctionSymbol?>()

    fun named(name: String): IrSimpleFunctionSymbol? = cache.getOrPut(name) {
        finder.findFunctions(CallableId(DebugInputNames.REGISTRY, Name.identifier(name)))
            .singleOrNull()
    }
}
