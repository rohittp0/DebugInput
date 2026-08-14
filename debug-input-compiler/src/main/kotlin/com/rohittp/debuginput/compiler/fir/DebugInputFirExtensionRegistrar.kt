package com.rohittp.debuginput.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/**
 * Wires the frontend half in.
 *
 * The checkers go in unconditionally; the descriptor function declaration only when the
 * transform is enabled, so an Android release compilation gets the diagnostics and nothing
 * else (ADR-0002).
 */
internal class DebugInputFirExtensionRegistrar(
    private val module: String,
    private val transformEnabled: Boolean,
) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +::DebugInputCheckersExtension
        registerDiagnosticContainers(DebugInputErrors)

        if (transformEnabled) {
            +{ session: FirSession -> DebugInputDescriptorFunctionGenerator(session, module) }
        }
    }
}

internal class DebugInputCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val propertyCheckers: Set<FirDeclarationChecker<FirProperty>> =
            setOf(DebugInputPropertyChecker)

        override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> =
            setOf(DebugInputEnumClassChecker)
    }
}
