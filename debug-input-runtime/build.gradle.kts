import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// This artifact must stay dependency-free. Anything added here lands in every
// instrumented module of every consumer, including pure-logic ones.
kotlin {
    explicitApi()

    android {
        namespace = "com.rohittp.debuginput"
        compileSdk = 37
        minSdk = 24
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        // DebugInputInternalApi marks API that only this project and debug-input-compose
        // should touch. Requiring this module to opt in to its own annotation is pure
        // ceremony, so it is opted in wholesale here.
        all {
            languageSettings.optIn("com.rohittp.debuginput.DebugInputInternalApi")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        // Test-only. Nothing here reaches a consumer.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
        }
    }
}
