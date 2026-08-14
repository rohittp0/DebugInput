package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.compiler.fir.DebugInputFirExtensionRegistrar
import com.rohittp.debuginput.compiler.ir.DebugInputIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * Entry point the compiler discovers through `META-INF/services`.
 *
 * The frontend half is registered unconditionally and the backend half only when
 * `enabled=true`. That asymmetry is the whole of ADR-0002: an Android release compilation
 * must produce no registry call and no input id, but must still reject a `const val` or a
 * `var` so the misuse cannot hide until release.
 */
public class DebugInputCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = DEBUG_INPUT_PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(
            DebugInputFirExtensionRegistrar(
                module = configuration.debugInputModule,
                transformEnabled = configuration.debugInputEnabled,
            ),
        )

        if (configuration.debugInputEnabled) {
            IrGenerationExtension.registerExtension(
                DebugInputIrGenerationExtension(
                    module = configuration.debugInputModule,
                    manifestOut = configuration.get(DebugInputConfigurationKeys.MANIFEST_OUT),
                    dependencyDescriptors = configuration
                        .getList(DebugInputConfigurationKeys.DEPENDENCY_DESCRIPTORS),
                ),
            )
        }
    }
}
