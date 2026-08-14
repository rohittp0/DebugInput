import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    `maven-publish`
}

kotlin {
    explicitApi()

    android {
        namespace = "com.rohittp.debuginput.compose"
        compileSdk = 37
        minSdk = 24
        // No `withHostTest {}` here, unlike :debug-input-runtime. Every test in this
        // module renders the page, and androidx.compose.ui.test refuses to run on a
        // plain JVM: it reaches for Robolectric and dies on a null Build.FINGERPRINT.
        // The commonTest suite therefore runs on the simulator, via
        // :debug-input-compose:iosSimulatorArm64Test. Restore this line — and add
        // Robolectric plus a JVM-only runner — if the suite ever needs to run on the JVM.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    // No iosX64: Compose Multiplatform 1.12.0-rc01 publishes no iosX64 variant of
    // runtime, foundation or ui, so the target cannot resolve its dependencies. The
    // Intel iOS simulator is gone upstream. :debug-input-runtime keeps the target —
    // it has no Compose on it — so a consumer building for iosX64 still gets working
    // debug inputs, just no page.

    sourceSets {
        // The page reads and writes overrides through DebugInputInternalApi on nearly
        // every line, so it opts in once here rather than in a @file: annotation on each
        // file. Same reasoning as :debug-input-runtime.
        all {
            languageSettings.optIn("com.rohittp.debuginput.DebugInputInternalApi")
        }

        commonMain.dependencies {
            api(project(":debug-input-runtime"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            // Material 3 is versioned on a line of its own; see the composeMaterial3
            // entry in the version catalog.
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.ui.test)
        }
    }
}
