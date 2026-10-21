plugins {
    id("com.android.application")
}

// LF for everything packaged byte for byte, so the APK matches F-Droid's build on any OS.
apply { from("../gradle/line-endings.gradle.kts") }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

android {
    namespace = "io.github.antonkulaga.glucowatch.watchface"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.antonkulaga.glucowatch.watchface"
        // Watch Face Format v1 needs Wear OS 4 (API 33) or newer.
        minSdk = 33
        targetSdk = 36
        versionCode = 17
        versionName = "0.1.16"
    }

    buildTypes {
        release {
            // AGP adds the Kotlin stdlib to every module. R8 drops it, since the face has no code.
            isMinifyEnabled = true
            isShrinkResources = false
        }
    }

    packaging {
        resources.excludes += "kotlin/**"
    }
}
