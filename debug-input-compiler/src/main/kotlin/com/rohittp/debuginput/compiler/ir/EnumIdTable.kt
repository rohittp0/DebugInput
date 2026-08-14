// See DebugInputIrGenerationExtension for why the opt-in is safe here.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.rohittp.debuginput.compiler.ir

import com.rohittp.debuginput.compiler.fir.DebugInputGeneratedKey
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * The ids of one enum-class input, in ordinal order, behind a top-level `val` in the enum's own
 * file — plus the two calls a getter needs to index it.
 *
 * The ids cannot be baked into the getter as literals, because one getter serves every constant.
 * They also cannot be built by concatenation at read time: these constants are read inside frame
 * loops, and a string per read would be a measurable allocation in a debug build. So the table is
 * built once, when the file's initialisers run, and every read is an array load.
 */
internal class EnumIdTable(
    private val tableGetter: IrSimpleFunctionSymbol,
    private val arrayGet: IrSimpleFunctionSymbol,
    private val ordinalGetter: IrSimpleFunctionSymbol,
) {
    /** `$debugInputIds$Enum$prop[receiver.ordinal]` */
    fun idOf(builder: IrBuilderWithScope, receiver: IrExpression): IrExpression =
        with(builder) {
            irCall(arrayGet).apply {
                arguments[0] = irCall(tableGetter)
                arguments[1] = irCall(ordinalGetter).apply { arguments[0] = receiver }
            }
        }
}

/**
 * Emits the table and resolves the two accessors. Returns null when anything it needs is missing,
 * which leaves the getter alone rather than half-rewritten.
 */
internal fun emitEnumIdTable(
    pluginContext: IrPluginContext,
    finder: DeclarationFinder,
    file: IrFile,
    enumClass: IrClass,
    property: IrProperty,
    idsByOrdinal: List<String>,
): EnumIdTable? {
    val arrayOf = finder.findFunctions(ARRAY_OF)
        .singleOrNull { it.owner.parameters.singleOrNull()?.varargElementType != null } ?: return null
    val arrayGet = pluginContext.irBuiltIns.arrayClass.owner.functions
        .singleOrNull { it.name.asString() == "get" }?.symbol ?: return null
    // `ordinal` is inherited from kotlin.Enum, so the enum class itself carries a fake override of
    // it — including for a constant with a body, which is an anonymous subclass but still an Enum.
    val ordinalGetter = enumClass.declarations.filterIsInstance<IrProperty>()
        .singleOrNull { it.name.asString() == "ordinal" }?.getter?.symbol
        ?: finder.findClass(ENUM)?.owner?.declarations?.filterIsInstance<IrProperty>()
            ?.singleOrNull { it.name.asString() == "ordinal" }?.getter?.symbol
        ?: return null

    val stringArrayType = pluginContext.irBuiltIns.arrayClass.typeWith(pluginContext.irBuiltIns.stringType)
    val tableName = "\$debugInputIds\$${enumClass.name.asString()}\$${property.name.asString()}"

    val table = pluginContext.irFactory.buildProperty {
        name = Name.identifier(tableName)
        origin = IrDeclarationOrigin.GeneratedByPlugin(DebugInputGeneratedKey)
        // Public because the enum's getter is a different class in the same file, and a private
        // Kotlin field cannot be read across classes without a synthetic accessor. The declaration
        // has no metadata, so no Kotlin source can name it whatever its visibility says.
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
    }
    table.parent = file
    file.declarations += table

    val field = table.addBackingField {
        type = stringArrayType
        origin = IrDeclarationOrigin.GeneratedByPlugin(DebugInputGeneratedKey)
        isStatic = true
        isFinal = true
        visibility = DescriptorVisibilities.PRIVATE
    }
    field.parent = file

    val builder = DeclarationIrBuilder(pluginContext, table.symbol)
    field.initializer = pluginContext.irFactory.createExpressionBody(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        builder.irCall(arrayOf).apply {
            typeArguments[0] = pluginContext.irBuiltIns.stringType
            arguments[0] = builder.irVararg(
                pluginContext.irBuiltIns.stringType,
                idsByOrdinal.map { builder.irString(it) },
            )
        },
    )

    val getter = pluginContext.irFactory.buildFun {
        name = Name.special("<get-$tableName>")
        origin = IrDeclarationOrigin.GeneratedByPlugin(DebugInputGeneratedKey)
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        returnType = stringArrayType
    }
    getter.parent = file
    getter.correspondingPropertySymbol = table.symbol
    table.getter = getter
    getter.body = DeclarationIrBuilder(pluginContext, getter.symbol).irBlockBody {
        +irReturn(irGetField(null, field))
    }

    return EnumIdTable(getter.symbol, arrayGet, ordinalGetter)
}

private val ARRAY_OF = CallableId(FqName("kotlin"), Name.identifier("arrayOf"))

private val ENUM = org.jetbrains.kotlin.name.ClassId(FqName("kotlin"), Name.identifier("Enum"))
