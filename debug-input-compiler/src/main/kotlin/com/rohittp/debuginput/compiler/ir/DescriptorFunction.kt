// See DebugInputIrGenerationExtension for why the opt-in is safe here.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.rohittp.debuginput.compiler.ir

import com.rohittp.debuginput.compiler.DebugInputNames
import com.rohittp.debuginput.compiler.descriptorFunctionName
import com.rohittp.debuginput.compiler.fir.DebugInputGeneratedKey
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObjectValue
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Fills in the body of the descriptor function the frontend declared, and the per-file helpers
 * it delegates to:
 *
 * ```
 * // in Physics.kt
 * fun `$debugInputDescriptors$Physics_kt`(): List<DebugInputDescriptor> =
 *     mutableListOf(DebugInputDescriptor(…), DebugInputDescriptor(…))
 *
 * // in the generated file
 * @Deprecated("…", level = DeprecationLevel.HIDDEN)
 * public fun descriptors_domain(): List<DebugInputDescriptor> {
 *     val all = mutableListOf<DebugInputDescriptor>()
 *     all.addAll(`$debugInputDescriptors$Physics_kt`())
 *     all.addAll(descriptors_shared())
 *     return all
 * }
 * ```
 *
 * The per-file split is forced by where the defaults live. `default` is read out of the
 * property's own backing field, and a backing field is readable only from its own file: the IR
 * validator rejects `Access to a field declared in another file`, and widening the field is
 * rejected in turn by `Kotlin fields are expected to always be private`. Both were verified on
 * the Native backend against 2.3.21. So each file builds its own descriptors and the module
 * function only concatenates.
 *
 * The dependency calls are what makes aggregation hierarchical, and the same field-locality rule
 * is why it has to be — see docs/adr/0006-linkage-by-call-site-rewriting.md.
 *
 * `default` is read eagerly rather than wrapped in a provider lambda. A `val`'s default cannot
 * change, so a lambda would only buy IR complexity. It does mean building the list forces the
 * declaring files' initialisers, which a first read would run anyway.
 */
internal class DescriptorFunctionEmitter(
    private val pluginContext: IrPluginContext,
    private val module: String,
    private val dependencyDescriptors: List<String>,
) {

    /**
     * Returns the filled-in function, or null when it or the runtime types it needs cannot be
     * resolved — a module with no `debug-input-runtime` on its compile classpath, which by
     * construction has no inputs and no page either.
     */
    fun emit(moduleFragment: IrModuleFragment, sites: List<DebugInputSite>): IrSimpleFunction? {
        val functions = moduleFragment.descriptorFunctions()
        if (functions.isEmpty()) return null

        val generatedFiles = functions.mapTo(mutableSetOf()) { it.parent }
        // Anchored on a file the module actually declared, never on the generated one: the
        // finder resolves against what that file can see, and the generated file's own
        // dependency context is not something to rely on.
        val anchor = moduleFragment.files.firstOrNull { it !in generatedFiles } ?: return null
        val symbols = RuntimeSymbols.resolve(pluginContext, anchor) ?: return null

        val perFile = sites.groupBy { it.file }
            .map { (file, inFile) -> emitFileDescriptors(file, inFile, symbols) }
        val dependencies = resolveDependencies(pluginContext.finderForSource(anchor))

        // Every match, not just the first: a body-less generated function is a hard backend
        // failure, so if the frontend ever hands us more than one, the diagnostic the user gets
        // should be the real signature clash rather than "Function has no body".
        for (function in functions) {
            function.body = DeclarationIrBuilder(pluginContext, function.symbol).irBlockBody {
                val all = irTemporary(
                    value = irCall(symbols.mutableListOf).apply {
                        typeArguments[0] = symbols.descriptorType
                        arguments[0] = irVararg(symbols.descriptorType, emptyList())
                    },
                    nameHint = "all",
                )
                for (contributor in perFile + dependencies) {
                    +irCall(symbols.addAll).apply {
                        arguments[0] = irGet(all)
                        arguments[1] = irCall(contributor)
                    }
                }
                +irReturn(irGet(all))
            }
        }

        return functions.first()
    }

    /**
     * One helper per file with inputs, emitted into that file so its field reads are local.
     *
     * The name carries the file's own name because top-level declarations share a package
     * namespace: two files in one package would otherwise collide. It is unnameable from
     * Kotlin source and absent from metadata, so nothing outside generated code can reach it.
     */
    private fun emitFileDescriptors(
        file: IrFile,
        sites: List<DebugInputSite>,
        symbols: RuntimeSymbols,
    ): IrSimpleFunctionSymbol {
        val function = pluginContext.irFactory.buildFun {
            name = Name.identifier("\$debugInputDescriptors\$${file.mangledBaseName}")
            origin = IrDeclarationOrigin.GeneratedByPlugin(DebugInputGeneratedKey)
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            returnType = symbols.listOfDescriptorType
        }
        function.parent = file
        file.declarations += function

        function.body = DeclarationIrBuilder(pluginContext, function.symbol).irBlockBody {
            +irReturn(
                irCall(symbols.mutableListOf).apply {
                    typeArguments[0] = symbols.descriptorType
                    arguments[0] = irVararg(
                        symbols.descriptorType,
                        sites.map { descriptorOf(it, symbols.descriptorConstructor) },
                    )
                },
            )
        }
        return function.symbol
    }

    /**
     * The functions the frontend generated for this module, found by walking IR rather than by
     * resolving the name.
     *
     * Resolution was the original approach and it was too fragile: it depends on which file the
     * finder is anchored to and on the provider returning exactly one symbol, and a
     * `singleOrNull` that silently returned null left the function with no body, which surfaces
     * only as a backend crash. What is in the module fragment is the thing that has to be given
     * a body, so look there.
     */
    private fun IrModuleFragment.descriptorFunctions(): List<IrSimpleFunction> {
        val expectedName = Name.identifier(descriptorFunctionName(module))
        return files
            .filter { it.packageFqName == DebugInputNames.GENERATED_PACKAGE }
            .flatMap { it.declarations }
            .filterIsInstance<IrSimpleFunction>()
            .filter { it.name == expectedName && it.origin.isDebugInputGenerated() }
    }

    private fun IrDeclarationOrigin.isDebugInputGenerated(): Boolean =
        this is IrDeclarationOrigin.GeneratedByPlugin && pluginKey == DebugInputGeneratedKey

    /**
     * Arguments are placed by parameter name, not position. `DebugInputDescriptor` gained a
     * parameter mid-milestone, and positional assignment would have kept compiling while quietly
     * putting values in the wrong fields.
     *
     * Parameters the plugin says nothing about — `enumConstants` until M3 — are left for the
     * constructor's own defaults to fill.
     */
    private fun IrBuilderWithScope.descriptorOf(
        site: DebugInputSite,
        constructor: IrConstructorSymbol,
    ): IrExpression = irCallConstructor(constructor, emptyList()).apply {
        val parameters = constructor.owner.parameters
        fun set(parameterName: String, value: IrExpression) {
            val index = parameters.indexOfFirst { it.name.asString() == parameterName }
            check(index >= 0) {
                "DebugInputDescriptor has no parameter called $parameterName. The plugin and " +
                    "debug-input-runtime are from different versions."
            }
            arguments[index] = value
        }

        set("id", irString(site.id))
        set("displayName", irString(site.displayName))
        set("module", irString(module))
        set("section", irString(site.section))
        set("typeKey", irString(site.typeKey))
        set("docs", irString(site.docs))
        set("default", defaultOf(site))
        // The page picks its renderer off this, so a wrong spec is a wrong editor.
        set("spec", irString(site.spec))
    }

    /**
     * Reads the input's default straight out of its backing field, which is legal because this
     * runs inside a helper emitted into the declaring file.
     *
     * An instance property of an ordinary class has no field to read without an instance, so its
     * descriptor carries a null default. The getter rewrite still works there — the page just
     * cannot show what the value started as. A frontend diagnostic for that shape is the real fix
     * and is not in M1.
     */
    private fun IrBuilderWithScope.defaultOf(site: DebugInputSite): IrExpression {
        val field = site.property.backingField ?: return irNull()

        // An enum constant is a statically reachable singleton, so its field is readable without an
        // instance. Read the field and not the property: the getter now resolves through the
        // registry, and a descriptor is supposed to record what the value started as.
        site.enumEntry?.let { entry ->
            val enumClass = site.property.parent as? IrClass ?: return irNull()
            return irGetField(
                IrGetEnumValueImpl(startOffset, endOffset, enumClass.defaultType, entry.symbol),
                field,
            )
        }

        if (field.isStatic) return irGetField(null, field)

        val owner = site.property.parent as? IrClass ?: return irNull()
        if (owner.kind != ClassKind.OBJECT) return irNull()
        return irGetField(irGetObjectValue(owner.defaultType, owner.symbol), field)
    }

    /**
     * A dependency's function comes from an already-compiled module, so `firstOrNull` rather than
     * `singleOrNull`: dropping a whole module's inputs because its provider answered twice would
     * be a silent, hard-to-trace hole in the page.
     */
    private fun resolveDependencies(finder: DeclarationFinder): List<IrSimpleFunctionSymbol> =
        dependencyDescriptors.mapNotNull { fqName ->
            val packageName = fqName.substringBeforeLast('.', missingDelimiterValue = "")
            if (packageName.isEmpty()) return@mapNotNull null
            val callableId =
                CallableId(FqName(packageName), Name.identifier(fqName.substringAfterLast('.')))
            finder.findFunctions(callableId).firstOrNull { it.owner.parameters.isEmpty() }
        }
}

/** `Physics.kt` → `Physics_kt`, so it can sit inside a Kotlin identifier. */
private val IrFile.mangledBaseName: String
    get() = fileEntry.name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
        .joinToString("")

/** The handful of runtime and stdlib symbols the descriptor bodies are built from. */
private class RuntimeSymbols(
    val descriptorType: IrType,
    val listOfDescriptorType: IrType,
    val descriptorConstructor: IrConstructorSymbol,
    val mutableListOf: IrSimpleFunctionSymbol,
    val addAll: IrSimpleFunctionSymbol,
) {
    companion object {
        fun resolve(pluginContext: IrPluginContext, anchor: IrFile): RuntimeSymbols? {
            val finder = pluginContext.finderForSource(anchor)
            val descriptorClass = finder.findClass(DebugInputNames.DESCRIPTOR) ?: return null
            val descriptorConstructor =
                finder.findConstructors(DebugInputNames.DESCRIPTOR).singleOrNull() ?: return null
            val mutableListOf = finder.findFunctions(DebugInputNames.MUTABLE_LIST_OF)
                .singleOrNull { it.owner.parameters.singleOrNull()?.varargElementType != null }
                ?: return null
            val addAll = pluginContext.irBuiltIns.mutableListClass.owner.functions
                .singleOrNull { it.name.asString() == "addAll" && it.parameters.size == 2 }
                ?.symbol ?: return null

            val descriptorType = descriptorClass.owner.defaultType
            return RuntimeSymbols(
                descriptorType = descriptorType,
                listOfDescriptorType = pluginContext.irBuiltIns.listClass.typeWith(descriptorType),
                descriptorConstructor = descriptorConstructor,
                mutableListOf = mutableListOf,
                addAll = addAll,
            )
        }
    }
}
