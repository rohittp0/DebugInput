package com.rohittp.debuginput.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/** Plugin id shared by the Gradle plugin, the registrar and every CLI option. */
public const val DEBUG_INPUT_PLUGIN_ID: String = "com.rohittp.debug-input"

/**
 * Where [DebugInputCommandLineProcessor] parks each option for
 * [DebugInputCompilerPluginRegistrar] to pick up.
 */
internal object DebugInputConfigurationKeys {
    val ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("debug-input: run the IR transform")
    val MODULE: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("debug-input: declaring Gradle project path")
    val MANIFEST_OUT: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("debug-input: descriptor manifest output path")
    val DEPENDENCY_DESCRIPTORS: CompilerConfigurationKey<List<String>> =
        CompilerConfigurationKey.create("debug-input: dependency descriptor function names")
}

internal val ENABLED_OPTION = CliOption(
    optionName = "enabled",
    valueDescription = "true|false",
    description = "Run the IR transform. False on Android release compilations, where FIR " +
        "diagnostics still run so a misuse fails the release build too.",
    required = false,
)

internal val MODULE_OPTION = CliOption(
    optionName = "module",
    valueDescription = "<gradle project path>",
    description = "Gradle project path an input is declared in, e.g. :domain. " +
        "Defaults to the compiler module name.",
    required = false,
)

internal val MANIFEST_OUT_OPTION = CliOption(
    optionName = "manifestOut",
    valueDescription = "<path>",
    description = "Where to write this module's descriptor manifest.",
    required = false,
)

internal val DEPENDENCY_DESCRIPTORS_OPTION = CliOption(
    optionName = "dependencyDescriptors",
    valueDescription = "<fully qualified function name>",
    description = "Descriptor function of one direct dependency. Repeatable.",
    required = false,
    allowMultipleOccurrences = true,
)

/**
 * Parses `-P plugin:com.rohittp.debug-input:<option>=<value>` into the compiler
 * configuration.
 */
public class DebugInputCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = DEBUG_INPUT_PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        ENABLED_OPTION,
        MODULE_OPTION,
        MANIFEST_OUT_OPTION,
        DEPENDENCY_DESCRIPTORS_OPTION,
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option) {
            ENABLED_OPTION -> configuration.put(
                DebugInputConfigurationKeys.ENABLED,
                value.toBooleanStrictOrNull()
                    ?: throw CliOptionProcessingException("enabled expects true or false, got $value"),
            )

            MODULE_OPTION -> configuration.put(DebugInputConfigurationKeys.MODULE, value)

            MANIFEST_OUT_OPTION -> configuration.put(DebugInputConfigurationKeys.MANIFEST_OUT, value)

            DEPENDENCY_DESCRIPTORS_OPTION ->
                configuration.add(DebugInputConfigurationKeys.DEPENDENCY_DESCRIPTORS, value)

            else -> throw CliOptionProcessingException("Unknown debug-input option: ${option.optionName}")
        }
    }
}

/** True unless an Android release compilation turned the transform off (ADR-0002). */
internal val CompilerConfiguration.debugInputEnabled: Boolean
    get() = get(DebugInputConfigurationKeys.ENABLED, true)

/**
 * The Gradle project path inputs in this compilation belong to. The Gradle plugin always
 * passes it; the fallback keeps a bare `kotlinc` invocation working.
 */
internal val CompilerConfiguration.debugInputModule: String
    get() = get(DebugInputConfigurationKeys.MODULE)
        ?: get(CommonConfigurationKeys.MODULE_NAME)
        ?: "unknown"
