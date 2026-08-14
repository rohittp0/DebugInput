package com.rohittp.debuginput.compiler

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.Test

class PluginOptionsTest {

    private val processor = DebugInputCommandLineProcessor()

    @Test
    fun `the plugin id matches the one the Gradle plugin passes options under`() {
        assertEquals("com.rohittp.debug-input", processor.pluginId)
        assertEquals(DEBUG_INPUT_PLUGIN_ID, processor.pluginId)
    }

    @Test
    fun `the transform is on unless an option turns it off`() {
        assertTrue(CompilerConfiguration().debugInputEnabled)
        assertTrue(configuredWith("enabled" to "true").debugInputEnabled)
        assertTrue(!configuredWith("enabled" to "false").debugInputEnabled)
    }

    @Test
    fun `a non boolean enabled value is rejected rather than silently disabling the plugin`() {
        val failure = assertFailsWith<CliOptionProcessingException> {
            configuredWith("enabled" to "yes")
        }
        assertTrue("yes" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `module falls back to the compiler module name`() {
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "domain_debug")
        }
        assertEquals("domain_debug", configuration.debugInputModule)

        processor.processOption(MODULE_OPTION, ":domain", configuration)
        assertEquals(":domain", configuration.debugInputModule)
    }

    @Test
    fun `manifestOut and dependencyDescriptors are collected`() {
        val configuration = configuredWith(
            "manifestOut" to "/tmp/domain-descriptors.json",
            "dependencyDescriptors" to "com.rohittp.debuginput.generated.descriptors_domain",
            "dependencyDescriptors" to "com.rohittp.debuginput.generated.descriptors_shared",
        )

        assertEquals(
            "/tmp/domain-descriptors.json",
            configuration.get(DebugInputConfigurationKeys.MANIFEST_OUT),
        )
        assertEquals(
            listOf(
                "com.rohittp.debuginput.generated.descriptors_domain",
                "com.rohittp.debuginput.generated.descriptors_shared",
            ),
            configuration.getList(DebugInputConfigurationKeys.DEPENDENCY_DESCRIPTORS),
        )
    }

    @Test
    fun `dependencyDescriptors is the only repeatable option`() {
        val repeatable = processor.pluginOptions.filter { it.allowMultipleOccurrences }.map { it.optionName }
        assertEquals(listOf("dependencyDescriptors"), repeatable)
    }

    private fun configuredWith(vararg options: Pair<String, String>): CompilerConfiguration {
        val configuration = CompilerConfiguration()
        val byName = processor.pluginOptions.associateBy { it.optionName }
        for ((name, value) in options) {
            processor.processOption(requireNotNull(byName[name]) { "unknown option $name" }, value, configuration)
        }
        return configuration
    }
}
