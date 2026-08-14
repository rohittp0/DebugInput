package com.rohittp.debuginput.compiler.fir

import com.rohittp.debuginput.compiler.DEBUG_INPUT_PLUGIN_ID
import com.rohittp.debuginput.compiler.DebugInputNames
import com.rohittp.debuginput.compiler.descriptorFunctionName
import com.rohittp.debuginput.compiler.sanitizeModule
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.createCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.ConstantValueKind

/** Marks everything this plugin generates, so IR can tell its own declarations apart. */
internal object DebugInputGeneratedKey : GeneratedDeclarationKey() {
    override fun toString(): String = DEBUG_INPUT_PLUGIN_ID
}

/**
 * Declares this module's descriptor function in the **frontend**, empty. The body is filled
 * in by the IR half.
 *
 * Declaring it here rather than only in IR is not a style choice. A function that exists only
 * in IR is emitted into a synthetic file, and a synthetic file carries no Kotlin metadata
 * payload — verified against 2.3.21: the facade class comes out with `@Metadata(k = 3)` and no
 * declarations at all. A dependent module then cannot resolve the function by name, which is
 * exactly what `dependencyDescriptors` has to do. Going through FIR gives the function a real
 * `FirFile` to be serialized into.
 *
 * Registered only when the transform is enabled: an Android release compilation must not even
 * declare this (ADR-0002).
 *
 * Top-level declaration generation is behind an experimental opt-in in 2.3.21 and, per
 * KT-66735, is not covered by Kotlin's incremental compilation. Editing a module's inputs
 * therefore wants a non-incremental recompile of anything that aggregates it — a Gradle-side
 * concern, noted here because this is where the constraint comes from.
 */
@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
internal class DebugInputDescriptorFunctionGenerator(
    session: FirSession,
    private val module: String,
) : FirDeclarationGenerationExtension(session) {

    private val callableId = CallableId(
        DebugInputNames.GENERATED_PACKAGE,
        Name.identifier(descriptorFunctionName(module)),
    )

    /**
     * An HMPP compilation runs one frontend session per source set — for
     * `iosSimulatorArm64` that is `commonMain`, `nativeMain`, `appleMain`, `iosMain` and the
     * target itself — and each of them asks every generation extension for its top-level
     * declarations. Generating in all five produced five identical `descriptors_domain`
     * functions in one module, which Native rejects as a signature clash and JVM turns into a
     * body-less function. Caching inside the extension cannot help, because there is one
     * extension instance per session.
     *
     * The root of the `dependsOn` chain is the single session every compilation has exactly one
     * of, whether or not the module is multiplatform, so the declaration belongs to it. Its
     * source set is compiled into every target, so the function reaches every platform artifact.
     */
    private val ownsDeclaration: Boolean = session.moduleData.dependsOnDependencies.isEmpty()

    override fun getTopLevelCallableIds(): Set<CallableId> =
        if (ownsDeclaration) setOf(callableId) else emptySet()

    override fun hasPackage(packageFqName: FqName): Boolean =
        ownsDeclaration && packageFqName == DebugInputNames.GENERATED_PACKAGE

    /**
     * The compiler asks for a generated declaration more than once — once per scope that could
     * see it — and every answer has to be the *same* declaration. Building a fresh one each
     * time produced five identical `descriptors_domain` functions in a two-file module, which
     * the Native klib serializer rejects as a signature clash and the JVM backend turns into a
     * body-less function, because the IR half could no longer tell which one to fill.
     */
    private val cache: FirCache<CallableId, FirNamedFunction, Nothing?> =
        session.firCachesFactory.createCache { _, _ -> buildDescriptorFunction() }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirNamedFunctionSymbol> {
        if (!ownsDeclaration || callableId != this.callableId) return emptyList()
        return listOf(cache.getValue(callableId, null).symbol)
    }

    private fun buildDescriptorFunction(): FirNamedFunction {
        val descriptorType = DebugInputNames.DESCRIPTOR.createConeType(session)
        val returnType = StandardClassIds.List.createConeType(session, arrayOf(descriptorType))

        // One file per module: two instrumented modules in one consumer build would otherwise
        // both claim the JVM facade class for the same file name.
        val function = createTopLevelFunction(
            key = DebugInputGeneratedKey,
            callableId = callableId,
            returnType = returnType,
            containingFileName = "DebugInputDescriptors_${sanitizeModule(module)}",
        )
        function.replaceAnnotations(listOf(hiddenDeprecation()))
        return function
    }

    /**
     * `@Deprecated(level = HIDDEN)` in the metadata, not just in the class file. That is what
     * keeps the function out of a consumer's resolution and completion, and it is the half of
     * ADR-0006 that had to be verified: an IR-generated call is built against the symbol rather
     * than resolved through the scope tower, so it links across a module boundary anyway.
     */
    private fun hiddenDeprecation(): FirAnnotation = buildAnnotation {
        annotationTypeRef = buildResolvedTypeRef {
            coneType = StandardClassIds.Annotations.Deprecated.createConeType(session)
        }
        argumentMapping = buildAnnotationArgumentMapping {
            mapping[Name.identifier("message")] = buildLiteralExpression(
                source = null,
                kind = ConstantValueKind.String,
                value = HIDDEN_MESSAGE,
                setType = true,
            )
            mapping[Name.identifier("level")] = buildEnumEntryDeserializedAccessExpression {
                enumClassId = StandardClassIds.DeprecationLevel
                enumEntryName = Name.identifier("HIDDEN")
            }
        }
    }

    private companion object {
        const val HIDDEN_MESSAGE = "Generated by debug-input. Not part of any public API."
    }
}
