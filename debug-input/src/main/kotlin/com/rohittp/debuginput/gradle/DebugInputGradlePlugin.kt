package com.rohittp.debuginput.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

private const val GROUP = "com.rohittp"
private const val COMPILER_ARTIFACT = "debug-input-compiler"
private const val RUNTIME_ARTIFACT = "debug-input-runtime"

/** Matches the compiler plugin's `CommandLineProcessor.pluginId`. */
private const val COMPILER_PLUGIN_ID = "com.rohittp.debug-input"

/**
 * The `Usage` value that marks the one configuration carrying descriptor manifests.
 * `Usage` is matched by equality and every other configuration a Kotlin project exposes
 * carries a different one, so requesting this cannot accidentally select `apiElements`.
 */
private const val DESCRIPTORS_USAGE = "debug-input-descriptors"

private const val DESCRIPTOR_ELEMENTS = "debugInputDescriptorElements"
private const val VERSION_RESOURCE = "/com/rohittp/debuginput/gradle/debug-input-version.txt"

/**
 * Wires `debug-input-compiler` into a consumer's Kotlin compilations.
 *
 * Three things happen here that the compiler plugin cannot do for itself:
 *
 * - the Kotlin version is checked, because a mismatch is otherwise a `NoSuchMethodError`
 *   from inside FIR (ADR-0001);
 * - `debug-input-runtime` is added to every instrumented source set, because rewritten
 *   getters call `DebugInputRegistry` and the annotation has to resolve in release too;
 * - each direct project dependency's descriptor function is looked up, so the module's
 *   own descriptor function can call it (ADR-0006).
 *
 * `debug-input-compiler` itself reaches `kotlinCompilerPluginClasspath` through
 * [getPluginArtifact]; the Kotlin Gradle plugin adds it per compilation.
 */
public class DebugInputGradlePlugin : KotlinCompilerPluginSupportPlugin {

    /**
     * Source-set configurations the runtime has already been added to. One plugin
     * instance serves one project, and shared source sets are reached from every
     * compilation that reads them.
     */
    private val runtimeAddedTo = mutableSetOf<String>()

    override fun apply(target: Project) {
        val mismatch = kotlinVersionMismatchMessage(pluginVersion, target.getKotlinPluginVersion())
        if (mismatch != null) throw GradleException(mismatch)

        // Registered even when nothing turns out to be instrumented: a dependent project
        // resolves this by name, and an empty configuration is a clearer answer than a
        // missing one.
        target.configurations.consumable(DESCRIPTOR_ELEMENTS) {
            description = "Descriptor manifests produced by debug-input."
            attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                target.objects.named(Usage::class.java, DESCRIPTORS_USAGE),
            )
        }
    }

    override fun getCompilerPluginId(): String = COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(GROUP, COMPILER_ARTIFACT, pluginVersion)

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        DebugInputOptions.isInstrumented(
            platformType = kotlinCompilation.platformType.name,
            compilationName = kotlinCompilation.compilationName,
        )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        val platformType = kotlinCompilation.platformType.name
        val compilationName = kotlinCompilation.compilationName
        val projectPath = project.path
        val enabled = DebugInputOptions.isTransformEnabled(platformType, compilationName)

        addRuntimeDependency(kotlinCompilation)

        val manifestOut = project.layout.buildDirectory
            .file("debug-input/${kotlinCompilation.pathSegment}/descriptors.json")

        val dependencyManifests = if (enabled) {
            publishDescriptorManifest(kotlinCompilation, manifestOut)
            dependencyManifestContents(kotlinCompilation)
        } else {
            project.providers.provider { emptyList() }
        }

        // Everything the option list needs is either a plain value or a Provider that
        // resolves without a Project, so the returned Provider survives the configuration
        // cache.
        return dependencyManifests.map { contents ->
            DebugInputOptions.optionsFor(
                platformType = platformType,
                compilationName = compilationName,
                projectPath = projectPath,
                manifestOut = manifestOut.get().asFile.absolutePath,
                dependencyManifests = contents,
            )
        }
    }

    /**
     * Adds `debug-input-runtime` to every source set the compilation reads, not just its
     * default one: `@DebugInput` is usually written in a shared source set, and a
     * dependency on `androidDebug` does not make the annotation visible in `commonMain`.
     *
     * Added regardless of the `enabled` option, because the annotation has `BINARY`
     * retention and so has to resolve in Android release too.
     */
    private fun addRuntimeDependency(kotlinCompilation: KotlinCompilation<*>) {
        val dependencies = kotlinCompilation.project.dependencies
        kotlinCompilation.allKotlinSourceSets.forEach { sourceSet ->
            val configurationName = sourceSet.implementationConfigurationName
            if (runtimeAddedTo.add(configurationName)) {
                dependencies.add(configurationName, "$GROUP:$RUNTIME_ARTIFACT:$pluginVersion")
            }
        }
    }

    /**
     * Exposes this compilation's descriptor manifest to dependent projects. All
     * compilations share one configuration: they all report the same descriptor function
     * name, so a consumer only needs whichever manifest it can see.
     */
    private fun publishDescriptorManifest(
        kotlinCompilation: KotlinCompilation<*>,
        manifestOut: Provider<RegularFile>,
    ) {
        kotlinCompilation.project.configurations.named(DESCRIPTOR_ELEMENTS).configure {
            outgoing.artifact(manifestOut) {
                // buildDirectory.file() carries no task dependency of its own.
                builtBy(kotlinCompilation.compileTaskProvider)
            }
        }
    }

    /**
     * The descriptor manifests of this compilation's direct project dependencies.
     *
     * A dedicated resolvable configuration rather than a guess at the dependency's build
     * directory: the manifest path is the Gradle plugin's private choice, and only the
     * producing project can be trusted to say where it put it.
     *
     * `isTransitive = false` is what makes this *direct* dependencies — the descriptor
     * functions aggregate hierarchically (ADR-0006), so pulling in transitive ones would
     * list every input twice. The lenient artifact view is what lets an ordinary
     * dependency that has never heard of debug-input resolve to nothing instead of
     * failing.
     */
    private fun dependencyManifestContents(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<String>> {
        val project = kotlinCompilation.project
        val buckets = kotlinCompilation.allKotlinSourceSets
            .flatMap {
                listOf(
                    it.apiConfigurationName,
                    it.implementationConfigurationName,
                    it.compileOnlyConfigurationName,
                )
            }
            .mapNotNull(project.configurations::findByName)

        val descriptors = project.configurations.resolvable(
            "debugInputDescriptorsFor${kotlinCompilation.configurationSuffix}",
        ) {
            description = "Descriptor manifests of ${kotlinCompilation.name}'s project dependencies."
            isTransitive = false
            attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                project.objects.named(Usage::class.java, DESCRIPTORS_USAGE),
            )
            buckets.forEach(::extendsFrom)
        }

        val manifestFiles: FileCollection = descriptors.get().incoming
            .artifactView {
                lenient(true)
                componentFilter { it is ProjectComponentIdentifier }
            }
            .files

        // Declared as an input so the dependency's compile task runs first and a changed
        // manifest recompiles this module. The paths are irrelevant, only the contents.
        kotlinCompilation.compileTaskProvider.configure {
            inputs.files(manifestFiles)
                .withPropertyName("debugInputDependencyDescriptors")
                .withPathSensitivity(PathSensitivity.NONE)
                .optional()
        }

        return manifestFiles.elements.map { locations ->
            locations.map { it.asFile }.filter { it.isFile }.map { it.readText() }
        }
    }
}

/**
 * Build-directory path segment for one compilation. `disambiguatedName` renders a null
 * disambiguation classifier as the literal string `null`, so it is spelled out here.
 */
private val KotlinCompilation<*>.pathSegment: String
    get() = sequenceOf(target.name, compilationName).filter(String::isNotBlank).joinToString("/")

/** The same identity as [pathSegment], in the camel case Gradle names conventionally use. */
private val KotlinCompilation<*>.configurationSuffix: String
    get() = sequenceOf(target.name, compilationName)
        .filter(String::isNotBlank)
        .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

/**
 * This plugin's own version, which is also the version of the compiler and runtime
 * artifacts it asks for — the three are published together at one `VERSION_NAME`
 * (ADR-0003), so they are never resolved independently.
 */
private val pluginVersion: String by lazy {
    val resource = DebugInputGradlePlugin::class.java.getResourceAsStream(VERSION_RESOURCE)
        ?: error("debug-input is missing $VERSION_RESOURCE; the plugin jar is not built correctly.")
    resource.use { it.readBytes().decodeToString().trim() }
}
