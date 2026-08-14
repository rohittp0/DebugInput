plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

// The Gradle plugin adds this artifact to kotlinCompilerPluginClasspath by coordinate, so
// it has to be resolvable even though nobody writes it by hand.
publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        // FirDeclarationChecker.check takes its CheckerContext and DiagnosticReporter as
        // context parameters in 2.3.21, and the feature is still behind a flag there, so
        // overriding it is impossible without this.
        freeCompilerArgs.add("-Xcontext-parameters")

        // The compiler plugin API is experimental by construction; ADR-0001 pins the
        // Kotlin minor precisely so that this is a known cost rather than a surprise.
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

dependencies {
    // Provided by whichever Kotlin compiler daemon the consumer runs. A version
    // mismatch is caught by the Gradle plugin's guard, not by resolution.
    // See docs/adr/0001-pin-one-kotlin-minor-per-release.md.
    compileOnly(libs.kotlin.compiler.embeddable)

    // The test harness drives K2JVMCompiler in-process, so it needs the real thing.
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    // The in-process compiler needs the same module access the Kotlin daemon grants itself.
    jvmArgs(
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    )

    // The golden IR snapshots are read from, and rewritten in, the source tree rather than
    // the processed copy, so that UPDATE_GOLDEN=true produces a reviewable diff.
    systemProperty("debugInput.goldenDir", layout.projectDirectory.dir("src/test/resources/golden").asFile.path)
}
