pluginManagement {
    plugins {
        id("com.rohittp.debug-input") version providers.gradleProperty("debugInputVersion").get()
    }
    repositories {
        maven {
            name = "DebugInputUnderTest"
            url = uri(
                providers.gradleProperty("debugInputRepositoryUrl")
                    .orElse(file("../build/local-maven").toURI().toString())
                    .get(),
            )
            content {
                includeGroup("com.rohittp.debug-input")
                includeGroup("com.rohittp")
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "DebugInputUnderTest"
                    url = uri(
                        providers.gradleProperty("debugInputRepositoryUrl")
                            .orElse(file("../build/local-maven").toURI().toString())
                            .get(),
                    )
                }
            }
            filter {
                includeGroup("com.rohittp")
            }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                includeGroup("org.jetbrains.skiko")
            }
        }
    }
}

rootProject.name = "debug-input-consumer-smoke"
