// See DebugInputIrGenerationExtension for why the opt-in is safe here.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.rohittp.debuginput.compiler.ir

import com.rohittp.debuginput.compiler.DebugInputTypeShape
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.classId

/**
 * Reduces a backend type to the shape the shared classification rules work on.
 *
 * `mapLike` is left false: the frontend rejected every `Map` before IR ran, and a supertype walk
 * here would only be able to repeat that.
 */
internal fun IrType.toDebugInputShape(): DebugInputTypeShape {
    val owner = classOrNull?.owner
    return DebugInputTypeShape(
        classId = owner?.classId,
        isEnum = owner?.kind == ClassKind.ENUM_CLASS,
        isNullable = isMarkedNullable(),
        mapLike = false,
        arguments = (this as? IrSimpleType)?.arguments.orEmpty().map { argument ->
            (argument as? IrTypeProjection)?.type?.toDebugInputShape()
        },
    )
}
