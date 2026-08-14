import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Dogfood module. Never published. Hosts the DebugInputsPage() call site, so this is
// the module whose generated descriptor function aggregates :domain's inputs.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    android {
        namespace = "com.rohittp.debuginput.sample.shared"
        compileSdk = 37
        minSdk = 24
        // No withHostTest: the tests here render the page, and androidx.compose.ui.test
        // does not run on a plain JVM. They run on the simulator instead, the same way
        // :debug-input-compose's do. Android rendering is covered by :app:assembleDebug.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // No iosX64: Compose Multiplatform 1.12.0-rc01 publishes no iosX64 variant of
    // runtime, foundation or ui, so any module with Compose on it cannot resolve that
    // target. :debug-input-runtime keeps iosX64, so a consumer building for the Intel
    // simulator still gets working debug inputs — just no page.
    //
    // The frameworks exist so that both link paths are built. Linking is the only place
    // a debug and a release iOS binary diverge, and where Platform.isDebugBinary is
    // resolved, so linkReleaseFramework* is what proves iOS inertness is real.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SampleShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(project(":debug-input-compose"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        androidMain.dependencies {
            // ComposeView needs a lifecycle owner, which plain android.app.Activity
            // does not provide. :app uses ComponentActivity from here.
            api(libs.androidx.activity)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.ui.test)
        }
    }
}

// Hand-wired for the same reason :domain is; see docs/adr/0007-app-is-wired-by-hand.md.
configurations.matching { it.name.startsWith("kotlinCompilerPluginClasspath") }.configureEach {
    dependencies.add(project.dependencies.create(project(":debug-input-compiler")))
}

kotlin.targets.configureEach {
    val targetName = name
    compilations
        .matching { !it.name.contains("test", ignoreCase = true) }
        .configureEach {
            val manifest = layout.buildDirectory
                .file("debug-input/$targetName/$name/descriptors.json")
                .get().asFile
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.addAll(
                    "-P", "plugin:com.rohittp.debug-input:enabled=true",
                    "-P", "plugin:com.rohittp.debug-input:module=${project.path}",
                    "-P", "plugin:com.rohittp.debug-input:manifestOut=${manifest.absolutePath}",
                    // What the Gradle plugin computes from dependency manifests. Named by
                    // hand here, which is exactly the drift ADR-0007 accepts and the
                    // Gradle plugin's own tests guard against.
                    "-P", "plugin:com.rohittp.debug-input:dependencyDescriptors=" +
                        "com.rohittp.debuginput.generated.descriptors_domain",
                )
            }
        }
}
