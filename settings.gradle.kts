pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DebugInput"

// Published artifacts.
include(":debug-input")
include(":debug-input-compiler")
include(":debug-input-runtime")
include(":debug-input-compose")

// Dogfood modules. Never published. See docs/adr/0007-app-is-wired-by-hand.md.
// :domain holds the inputs, :shared hosts the DebugInputsPage() call site and the
// iOS targets, :app is the Android entry point. AGP 9 refuses to apply
// com.android.application alongside the Kotlin Multiplatform plugin, so the KMP
// half and the application half have to be separate modules.
include(":domain")
include(":shared")
include(":app")
