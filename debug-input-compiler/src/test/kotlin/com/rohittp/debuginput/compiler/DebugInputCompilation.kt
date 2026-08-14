package com.rohittp.debuginput.compiler

import java.io.File
import java.net.URLClassLoader
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.dumpKotlinLike

/** One source file to hand the compiler. [name] must end in `.kt`. */
internal class SourceFile(val name: String, val contents: String)

/** One diagnostic, flattened to what the tests actually assert on. */
internal data class Diagnostic(
    val severity: CompilerMessageSeverity,
    val message: String,
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "${severity.presentableName} ($line:$column) $message"
}

internal class CompilationResult(
    val exitCode: ExitCode,
    val diagnostics: List<Diagnostic>,
    /** Also the entry a dependent module puts on its compile classpath. */
    val classesDir: File,
    /** Kotlin-like IR dump per source file name, taken after the plugin ran. */
    val irDumps: Map<String, String>,
    /**
     * Every file in the module fragment, duplicates included. Two files of the same name is how
     * a declaration generated once per frontend session shows up, and the JVM backend hides that
     * by emitting only one of them.
     */
    val irFileNames: List<String>,
) {
    val errors: List<Diagnostic> get() = diagnostics.filter { it.severity.isError }

    private val loaders = mutableMapOf<List<File>, ClassLoader>()

    /**
     * Loads the compiled output. The parent loader supplies the runtime stubs.
     *
     * Memoized per classpath, because two loaders over the same output produce two distinct
     * classes with the same name: hand one loader's enum constant to the other loader's getter and
     * the cast inside it fails for no reason a test author would guess at.
     */
    fun classLoader(vararg alsoOnPath: File): ClassLoader {
        val path = listOf(classesDir) + alsoOnPath
        return loaders.getOrPut(path) {
            URLClassLoader(
                path.map { it.toURI().toURL() }.toTypedArray(),
                CompilationResult::class.java.classLoader,
            )
        }
    }

    fun assertSucceeded(): CompilationResult {
        check(exitCode == ExitCode.OK) {
            "Compilation failed with $exitCode:\n" + diagnostics.joinToString("\n")
        }
        return this
    }
}

/**
 * Drives the real `K2JVMCompiler` in this JVM, discovering the plugin the way a consumer's
 * build does — through `-Xplugin`, `META-INF/services` and
 * `-P plugin:com.rohittp.debug-input:…` — so option parsing and registration are under
 * test rather than stubbed.
 *
 * The service file it generates names [HarnessRegistrar] instead of
 * [DebugInputCompilerPluginRegistrar]. That wrapper delegates to the real registrar and
 * then appends one more IR extension, which is the only way to guarantee the golden dump
 * is taken *after* the getter rewrite: the compiler topologically sorts plugins, so two
 * separate registrars have no dependable order.
 */
internal fun compile(
    workDir: File,
    vararg sources: SourceFile,
    enabled: Boolean = true,
    module: String? = null,
    multiPlatform: Boolean = false,
    manifestOut: File? = null,
    dependencyDescriptors: List<String> = emptyList(),
    dependsOn: List<File> = emptyList(),
    /**
     * Source-set names, outermost first, each refining the one before it — the shape a
     * multiplatform module really compiles with. All sources go into the first, as they do in a
     * module whose inputs live in `commonMain`.
     *
     * This matters because the frontend runs **one session per source set** and asks every one
     * of them to generate its top-level declarations. A single-session compilation cannot show
     * that up.
     */
    sourceSetHierarchy: List<String> = emptyList(),
    /** False switches the frontend from its default LightTree parser to PSI, as the IDE uses. */
    lightTree: Boolean = true,
): CompilationResult {
    val sourceDir = File(workDir, "src").apply { mkdirs() }
    val classesDir = File(workDir, "classes").apply { mkdirs() }

    val sourceFiles = sources.map { source ->
        File(sourceDir, source.name).apply {
            parentFile.mkdirs()
            writeText(source.contents)
        }
    }

    val pluginOptions = buildList {
        add("plugin:$DEBUG_INPUT_PLUGIN_ID:enabled=$enabled")
        if (module != null) add("plugin:$DEBUG_INPUT_PLUGIN_ID:module=$module")
        if (manifestOut != null) add("plugin:$DEBUG_INPUT_PLUGIN_ID:manifestOut=${manifestOut.absolutePath}")
        for (dependency in dependencyDescriptors) {
            add("plugin:$DEBUG_INPUT_PLUGIN_ID:dependencyDescriptors=$dependency")
        }
    }

    val arguments = K2JVMCompilerArguments().apply {
        freeArgs = sourceFiles.map { it.absolutePath }
        destination = classesDir.absolutePath
        classpath = (compileClasspath + dependsOn).joinToString(File.pathSeparator)
        moduleName = "test-module"
        noStdlib = true
        noReflect = true
        this.multiPlatform = multiPlatform || sourceSetHierarchy.isNotEmpty()
        pluginClasspaths = arrayOf(harnessServiceDir(workDir).absolutePath)

        // On by default, not opt-in. The Native backend validates IR and the JVM one does not,
        // so without this the harness happily accepts IR that no iOS build would — which is
        // exactly how a plugin that widened a backing field and read it across files passed
        // every test here and failed on the first real module.
        useFirLT = lightTree
        verifyIr = "error"
        verifyIrVisibility = true

        if (sourceSetHierarchy.isNotEmpty()) {
            fragments = sourceSetHierarchy.toTypedArray()
            fragmentSources = sourceFiles
                .map { "${sourceSetHierarchy.first()}:${it.absolutePath}" }
                .toTypedArray()
            fragmentRefines = sourceSetHierarchy
                .zipWithNext { parent, child -> "$child:$parent" }
                .toTypedArray()
        }
        this.pluginOptions = pluginOptions.toTypedArray()
    }

    IrDumps.reset()
    val collector = RecordingMessageCollector()
    val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)

    return CompilationResult(exitCode, collector.diagnostics, classesDir, IrDumps.take(), IrDumps.fileNames())
}

/**
 * A `META-INF/services` tree the compiler can load the plugin from. The classes
 * themselves come from the test classpath, which is the plugin classloader's parent.
 */
private fun harnessServiceDir(workDir: File): File {
    val services = File(workDir, "plugin/META-INF/services").apply { mkdirs() }
    File(services, "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")
        .writeText(HarnessRegistrar::class.java.name)
    File(services, "org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor")
        .writeText(DebugInputCommandLineProcessor::class.java.name)
    return File(workDir, "plugin")
}

/**
 * The stubs plus the standard library, located through classes that live in them. Handing
 * over the whole test runtime classpath would put `kotlin-compiler-embeddable` in front of
 * every snippet for no reason.
 */
private val compileClasspath: List<File> by lazy {
    listOf(
        codeSourceOf("com.rohittp.debuginput.DebugInputRegistry"),
        codeSourceOf("kotlin.Unit"),
    )
}

private fun codeSourceOf(className: String): File {
    val location = Class.forName(className).protectionDomain.codeSource.location
    return File(location.toURI())
}

/**
 * Where [HarnessRegistrar] leaves its dumps. It has to be global: the compiler
 * instantiates the registrar itself through `ServiceLoader`, so there is nowhere to pass a
 * sink in.
 */
internal object IrDumps {

    private val byFileName = linkedMapOf<String, String>()

    private val names = mutableListOf<String>()

    fun reset() {
        byFileName.clear()
        names.clear()
    }

    fun put(fileName: String, dump: String) {
        byFileName[fileName] = dump
        names += fileName
    }

    fun take(): Map<String, String> = LinkedHashMap(byFileName)

    fun fileNames(): List<String> = names.toList()
}

/** Public only because `ServiceLoader` has to instantiate it. */
class HarnessRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = DEBUG_INPUT_PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        DebugInputCompilerPluginRegistrar().registerInto(this, configuration)

        IrGenerationExtension.registerExtension(
            object : IrGenerationExtension {
                override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                    for (file in moduleFragment.files) {
                        IrDumps.put(File(file.fileEntry.name).name, file.dumpKotlinLike())
                    }
                }
            },
        )
    }
}

/**
 * Calls [CompilerPluginRegistrar.registerExtensions] from outside any registrar, so the
 * call cannot resolve back to the caller and recurse.
 */
private fun CompilerPluginRegistrar.registerInto(
    storage: CompilerPluginRegistrar.ExtensionStorage,
    configuration: CompilerConfiguration,
) {
    with(storage) { registerExtensions(configuration) }
}

private class RecordingMessageCollector : MessageCollector {

    val diagnostics = mutableListOf<Diagnostic>()

    // The compiler clears its collector between phases; the tests need the whole run.
    override fun clear() = Unit

    override fun hasErrors(): Boolean = diagnostics.any { it.severity.isError }

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        diagnostics += Diagnostic(severity, message, location?.line ?: -1, location?.column ?: -1)
    }
}
