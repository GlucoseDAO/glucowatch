plugins {
    id("com.android.application")
}

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
        versionCode = 3
        versionName = "0.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}
