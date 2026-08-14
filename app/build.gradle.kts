// Dogfood Android app. Never published. Deliberately has no Kotlin Multiplatform and
// no Compose plugin of its own: it hosts a View that :shared hands it, so all the
// Compose work stays in the KMP module. AGP 9's built-in Kotlin support compiles the
// single Activity.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rohittp.debuginput.sample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rohittp.debuginput.sample"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
}
