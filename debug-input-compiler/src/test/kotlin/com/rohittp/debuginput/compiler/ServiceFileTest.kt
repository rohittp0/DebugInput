package com.rohittp.debuginput.compiler

import kotlin.test.assertEquals
import org.junit.Test

/**
 * The one thing the in-process harness deliberately bypasses: the `META-INF/services`
 * files a real compiler discovers the plugin through. A typo there fails silently, with the
 * plugin simply never running.
 */
class ServiceFileTest {

    @Test
    fun `the registrar is registered under its service name`() {
        assertEquals(
            DebugInputCompilerPluginRegistrar::class.java.name,
            serviceFile("org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"),
        )
    }

    @Test
    fun `the command line processor is registered under its service name`() {
        assertEquals(
            DebugInputCommandLineProcessor::class.java.name,
            serviceFile("org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor"),
        )
    }

    private fun serviceFile(serviceName: String): String {
        val resource = "/META-INF/services/$serviceName"
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing $resource" }
        return stream.bufferedReader().readText().trim()
    }
}
