import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Dogfood module. Never published. Holds @DebugInput properties in a module that
// :app depends on, so cross-module descriptor aggregation is exercised for real.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.rohittp.debuginput.sample.domain"
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
        commonMain.dependencies {
            implementation(project(":debug-input-runtime"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
        }
    }
}

// The compiler plugin is wired by hand rather than by applying com.rohittp.debug-input,
// because Gradle cannot apply a plugin from a sibling subproject in the same build.
// This is deliberate; see docs/adr/0007-app-is-wired-by-hand.md. The Gradle plugin must
// compute equivalent options, and its own tests are what hold the two in step.
configurations.matching { it.name.startsWith("kotlinCompilerPluginClasspath") }.configureEach {
    dependencies.add(project.dependencies.create(project(":debug-input-compiler")))
}

kotlin.targets.configureEach {
    val targetName = name
    compilations
        // Instrumenting a test compilation as well as its main one would put two
        // descriptor functions of the same name on one classpath.
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
                )
            }
        }
}
