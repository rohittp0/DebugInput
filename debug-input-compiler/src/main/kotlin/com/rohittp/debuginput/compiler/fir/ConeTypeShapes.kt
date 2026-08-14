package com.rohittp.debuginput.compiler.fir

import com.rohittp.debuginput.compiler.DebugInputTypeShape
import com.rohittp.debuginput.compiler.MAP_CLASS_ID
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isStarProjection
import org.jetbrains.kotlin.fir.types.type

/** Reduces a frontend type to the shape the shared classification rules work on. */
internal fun ConeKotlinType.toDebugInputShape(session: FirSession): DebugInputTypeShape {
    val symbol = session.classSymbolOf(this)
    return DebugInputTypeShape(
        classId = classId,
        isEnum = symbol?.classKind == ClassKind.ENUM_CLASS,
        isNullable = isMarkedNullable,
        mapLike = symbol != null && symbol.isMapLike(session),
        arguments = typeArguments.map { argument ->
            if (argument.isStarProjection) null else argument.type?.toDebugInputShape(session)
        },
    )
}

private fun FirSession.classSymbolOf(type: ConeKotlinType): FirClassSymbol<*>? {
    val classId = type.classId ?: return null
    return symbolProvider.getClassLikeSymbolByClassId(classId) as? FirClassSymbol<*>
}

/**
 * Walks supertypes rather than matching a fixed list of ids, because `Map` has to be rejected
 * "of any shape" and a consumer can reach one through a type alias, `HashMap`, or their own
 * implementation.
 */
private fun FirClassSymbol<*>.isMapLike(session: FirSession, depth: Int = 0): Boolean {
    if (classId == MAP_CLASS_ID) return true
    if (depth > MAX_SUPERTYPE_DEPTH) return false
    return resolvedSuperTypes.any { supertype ->
        val symbol = supertype.classId?.let {
            session.symbolProvider.getClassLikeSymbolByClassId(it)
        } as? FirClassSymbol<*>
        symbol != null && symbol.isMapLike(session, depth + 1)
    }
}

private const val MAX_SUPERTYPE_DEPTH = 12
