plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.3.21"
    id("com.rohittp.debug-input")
}

val debugInputVersion = providers.gradleProperty("debugInputVersion").get()

kotlin {
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The Gradle plugin adds debug-input-runtime. Resolving Compose here also
            // proves that the UI artifact and its target metadata were published.
            implementation("com.rohittp:debug-input-compose:$debugInputVersion")
        }
    }
}
