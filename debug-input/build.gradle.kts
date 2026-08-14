plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)

    // getKotlinPluginVersion() lives in the Kotlin Gradle plugin proper, not in its API
    // artifact, and the version guard has to read the compiler's own version (ADR-0001).
    compileOnly(libs.kotlin.gradle.plugin)

    // SubpluginOption is what the option computation returns, so the tests need at
    // runtime what production only compiles against.
    testImplementation(libs.kotlin.gradle.plugin.api)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

// The plugin asks for debug-input-compiler and debug-input-runtime at its own version,
// which CI overrides with -PVERSION_NAME, so it cannot be a constant in the source.
val pluginVersion = version.toString()

// Expanded across the whole resource tree rather than through a filesMatching action: a
// Kotlin lambda in a build script holds a reference to the script object, which the
// configuration cache cannot serialize. debug-input-version.txt is the only resource with
// a placeholder in it.
tasks.processResources {
    expand(mapOf("version" to pluginVersion))
}

gradlePlugin {
    plugins.create("debugInput") {
        id = "com.rohittp.debug-input"
        implementationClass = "com.rohittp.debuginput.gradle.DebugInputGradlePlugin"
        displayName = "debug-input"
        description = "Makes selected vals editable at runtime in debug builds."
    }
}

tasks.test {
    useJUnit()
}
